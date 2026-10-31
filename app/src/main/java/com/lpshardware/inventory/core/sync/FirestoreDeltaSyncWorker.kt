package com.lpshardware.inventory.core.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lpshardware.inventory.core.auth.GoogleAuthManager
import com.lpshardware.inventory.data.local.LpsDatabase
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.local.entity.SyncState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Bidirectional Firebase Cloud Firestore Sync Worker (`Push Local Dirty` + `Pull Remote Cloud to Local`).
 *
 * When a user logs in with Google on a new device:
 * 1. Authenticates requests with their Firebase ID Token (`Authorization: Bearer <idToken>`) if available.
 * 2. Pushes any local `PENDING_UPLOAD` items, variants, and sales in atomic batches.
 * 3. Pulls all remote `items`, `variants`, and `sales` from `/shops/{userCloudNamespace}/...`,
 *    automatically replacing initial starter demo items and hydrating the local Room SQLite database!
 */
class FirestoreDeltaSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = SyncManager.getConfig(applicationContext)
        if (!config.isConfigured) {
            return@withContext Result.success()
        }

        SyncManager.setSyncState(true, "Syncing with Firebase...")
        val db = LpsDatabase.getInstance(applicationContext)
        val invDao = db.inventoryDao()
        val saleDao = db.saleDao()

        val idToken = GoogleAuthManager.getValidFirebaseIdToken(applicationContext)
        val baseDocPath = "projects/${config.firebaseProjectId}/databases/(default)/documents/shops/${config.shopCollectionId}"
        val apiKeyParam = if (config.firebaseApiKey.isNotBlank()) "?key=${config.firebaseApiKey}" else ""

        try {
            // =========================================================================
            // PHASE 1: PUSH LOCAL DIRTY RECORDS (PENDING_UPLOAD) TO FIREBASE FIRESTORE
            // =========================================================================
            val pendingItems = invDao.getPendingSyncItems()
            val pendingVariants = invDao.getPendingSyncVariants()
            val pendingSales = saleDao.getPendingSyncSales()

            if (pendingItems.isNotEmpty() || pendingVariants.isNotEmpty() || pendingSales.isNotEmpty()) {
                val writesArray = JSONArray()

                for (item in pendingItems) {
                    val fields = JSONObject().apply {
                        put("displayName", strVal(item.displayName))
                        put("sortKey", strVal(item.sortKey))
                        put("category", strVal(item.category))
                        put("createdAt", intVal(item.createdAt))
                        put("updatedAt", intVal(item.updatedAt))
                        put("isDeleted", boolVal(item.isDeleted))
                    }
                    writesArray.put(
                        JSONObject().put(
                            "update",
                            JSONObject()
                                .put("name", "$baseDocPath/items/${item.id}")
                                .put("fields", fields)
                        )
                    )
                }

                for (variant in pendingVariants) {
                    val fields = JSONObject().apply {
                        put("itemId", strVal(variant.itemId))
                        put("sizeLabel", strVal(variant.sizeLabel))
                        put("materialLabel", strVal(variant.materialLabel))
                        put("colorFinishLabel", strVal(variant.colorFinishLabel))
                        put("unit", strVal(variant.unit))
                        put("purchasePricePaise", intVal(variant.purchasePricePaise))
                        put("sellingPricePaise", intVal(variant.sellingPricePaise))
                        put("stockQuantity", doubleVal(variant.stockQuantity))
                        put("lowStockThreshold", doubleVal(variant.lowStockThreshold))
                        put("updatedAt", intVal(variant.updatedAt))
                        put("isDeleted", boolVal(variant.isDeleted))
                    }
                    writesArray.put(
                        JSONObject().put(
                            "update",
                            JSONObject()
                                .put("name", "$baseDocPath/variants/${variant.id}")
                                .put("fields", fields)
                        )
                    )
                }

                for (sale in pendingSales) {
                    val fields = JSONObject().apply {
                        put("receiptNumber", strVal(sale.receiptNumber))
                        put("buyerName", strVal(sale.buyerName))
                        put("buyerPhone", strVal(sale.buyerPhone))
                        put("itemId", strVal(sale.itemId))
                        put("variantId", strVal(sale.variantId))
                        put("itemNameSnapshot", strVal(sale.itemNameSnapshot))
                        put("variantSummarySnapshot", strVal(sale.variantSummarySnapshot))
                        put("quantitySold", doubleVal(sale.quantitySold))
                        put("unitSnapshot", strVal(sale.unitSnapshot))
                        put("unitPurchasePricePaise", intVal(sale.unitPurchasePricePaise))
                        put("unitSellingPricePaise", intVal(sale.unitSellingPricePaise))
                        put("totalCostPaise", intVal(sale.totalCostPaise))
                        put("totalRevenuePaise", intVal(sale.totalRevenuePaise))
                        put("totalProfitPaise", intVal(sale.totalProfitPaise))
                        put("gainMarkupPercent", doubleVal(sale.gainMarkupPercent))
                        put("grossMarginPercent", doubleVal(sale.grossMarginPercent))
                        put("soldAt", intVal(sale.soldAt))
                        put("isReturned", boolVal(sale.isReturned))
                        put("updatedAt", intVal(sale.updatedAt))
                    }
                    writesArray.put(
                        JSONObject().put(
                            "update",
                            JSONObject()
                                .put("name", "$baseDocPath/sales/${sale.id}")
                                .put("fields", fields)
                        )
                    )
                }

                val commitPayload = JSONObject().put("writes", writesArray).toString()
                val commitUrl = "https://firestore.googleapis.com/v1/projects/${config.firebaseProjectId}/databases/(default)/documents:commit$apiKeyParam"

                val commitReqBuilder = Request.Builder()
                    .url(commitUrl)
                    .post(commitPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (!idToken.isNullOrBlank()) {
                    commitReqBuilder.header("Authorization", "Bearer $idToken")
                }

                httpClient.newCall(commitReqBuilder.build()).execute().use { response ->
                    if (!response.isSuccessful) {
                        SyncManager.setSyncState(false, "Push Retry Queued (${response.code})")
                        return@withContext Result.retry()
                    }
                    if (pendingItems.isNotEmpty()) invDao.markItemsSynced(pendingItems.map { it.id })
                    if (pendingVariants.isNotEmpty()) invDao.markVariantsSynced(pendingVariants.map { it.id })
                    if (pendingSales.isNotEmpty()) saleDao.markSalesSynced(pendingSales.map { it.id })
                }
            }

            // =========================================================================
            // PHASE 2: PULL REMOTE ITEMS, VARIANTS & SALES FROM FIREBASE FIRESTORE
            // (Hydrates all items locally upon Google Sign-In on a new device!)
            // =========================================================================
            val remoteItems = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "items",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteItem(doc) }.orEmpty()

            val remoteVariants = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "variants",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteVariant(doc) }.orEmpty()

            val remoteSales = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "sales",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteSale(doc) }.orEmpty()

            if (remoteItems.isNotEmpty() || remoteVariants.isNotEmpty() || remoteSales.isNotEmpty()) {
                val localItemsMap = invDao.getAllRawItems().associateBy { it.id }
                val localVariantsMap = invDao.getAllRawVariants().associateBy { it.id }
                val localSalesMap = saleDao.getAllRawSales().associateBy { it.id }

                // Only overwrite local records if remote is newer or does not exist locally
                val itemsToUpsert = remoteItems.filter { rem ->
                    val loc = localItemsMap[rem.id]
                    loc == null || (loc.syncStatus != SyncState.PENDING_UPLOAD && rem.updatedAt >= loc.updatedAt)
                }
                val variantsToUpsert = remoteVariants.filter { rem ->
                    val loc = localVariantsMap[rem.id]
                    loc == null || (loc.syncStatus != SyncState.PENDING_UPLOAD && rem.updatedAt >= loc.updatedAt)
                }
                val salesToUpsert = remoteSales.filter { rem ->
                    val loc = localSalesMap[rem.id]
                    loc == null || (loc.syncStatus != SyncState.PENDING_UPLOAD && rem.updatedAt >= loc.updatedAt)
                }

                db.withTransaction {
                    // If user has real cloud items on Firebase, remove any initial first-install demo items
                    if (remoteItems.isNotEmpty()) {
                        invDao.deleteDemoSeedVariants()
                        invDao.deleteDemoSeedItems()
                    }
                    if (itemsToUpsert.isNotEmpty()) invDao.upsertItems(itemsToUpsert)
                    if (variantsToUpsert.isNotEmpty()) invDao.upsertVariants(variantsToUpsert)
                    if (salesToUpsert.isNotEmpty()) saleDao.insertSales(salesToUpsert)
                }
            }

            val now = System.currentTimeMillis()
            SyncManager.updateLastSyncTimestamp(applicationContext, now)
            SyncManager.setSyncState(
                false,
                "Cloud Synced (${remoteItems.size} items)"
            )
            Result.success()
        } catch (e: Exception) {
            SyncManager.setSyncState(false, "Offline (Pending Sync)")
            Result.retry()
        }
    }

    private fun fetchAllDocumentsInCollection(
        projectId: String,
        shopCollectionId: String,
        subCollection: String,
        apiKey: String,
        idToken: String?
    ): List<JSONObject>? {
        val result = ArrayList<JSONObject>()
        var pageToken = ""
        var hasMorePages = true

        while (hasMorePages) {
            val queryParams = buildList {
                add("pageSize=250")
                if (apiKey.isNotBlank()) add("key=$apiKey")
                if (pageToken.isNotBlank()) add("pageToken=$pageToken")
            }.joinToString("&")

            val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/shops/$shopCollectionId/$subCollection?$queryParams"
            val reqBuilder = Request.Builder().url(url).get()
            if (!idToken.isNullOrBlank()) {
                reqBuilder.header("Authorization", "Bearer $idToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code == 404) return emptyList()
                if (!resp.isSuccessful) return null

                val bodyStr = resp.body?.string().orEmpty()
                if (bodyStr.isBlank()) return result
                val root = JSONObject(bodyStr)
                val docs = root.optJSONArray("documents")
                if (docs != null) {
                    for (i in 0 until docs.length()) {
                        result.add(docs.getJSONObject(i))
                    }
                }
                pageToken = root.optString("nextPageToken", "")
                if (pageToken.isBlank()) {
                    hasMorePages = false
                }
            }
        }
        return result
    }

    private fun parseRemoteItem(doc: JSONObject): InventoryItemEntity? {
        return try {
            val fullName = doc.getString("name")
            val id = fullName.substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val displayName = readStr(fields, "displayName")
            if (displayName.isBlank()) return null

            InventoryItemEntity(
                id = id,
                displayName = displayName,
                sortKey = readStr(fields, "sortKey").ifEmpty { displayName.uppercase() },
                category = readStr(fields, "category").ifEmpty { "General Hardware" },
                createdAt = readLong(fields, "createdAt", System.currentTimeMillis()),
                updatedAt = readLong(fields, "updatedAt", System.currentTimeMillis()),
                isDeleted = readBool(fields, "isDeleted", false),
                syncStatus = SyncState.SYNCED
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRemoteVariant(doc: JSONObject): ItemVariantEntity? {
        return try {
            val fullName = doc.getString("name")
            val id = fullName.substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val itemId = readStr(fields, "itemId")
            if (itemId.isBlank()) return null

            ItemVariantEntity(
                id = id,
                itemId = itemId,
                sizeLabel = readStr(fields, "sizeLabel"),
                materialLabel = readStr(fields, "materialLabel"),
                colorFinishLabel = readStr(fields, "colorFinishLabel"),
                unit = readStr(fields, "unit").ifEmpty { "pcs" },
                purchasePricePaise = readLong(fields, "purchasePricePaise", 0L),
                sellingPricePaise = readLong(fields, "sellingPricePaise", 0L),
                stockQuantity = readDouble(fields, "stockQuantity", 0.0),
                lowStockThreshold = readDouble(fields, "lowStockThreshold", 5.0),
                updatedAt = readLong(fields, "updatedAt", System.currentTimeMillis()),
                isDeleted = readBool(fields, "isDeleted", false),
                syncStatus = SyncState.SYNCED
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRemoteSale(doc: JSONObject): SaleRecordEntity? {
        return try {
            val fullName = doc.getString("name")
            val id = fullName.substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val soldAt = readLong(fields, "soldAt", System.currentTimeMillis())
            val cal = Calendar.getInstance().apply { timeInMillis = soldAt }

            SaleRecordEntity(
                id = id,
                receiptNumber = readStr(fields, "receiptNumber").ifEmpty { "LPS-0000" },
                buyerName = readStr(fields, "buyerName"),
                buyerPhone = readStr(fields, "buyerPhone"),
                itemId = readStr(fields, "itemId"),
                variantId = readStr(fields, "variantId"),
                itemNameSnapshot = readStr(fields, "itemNameSnapshot"),
                variantSummarySnapshot = readStr(fields, "variantSummarySnapshot").ifEmpty { "Standard" },
                quantitySold = readDouble(fields, "quantitySold", 1.0),
                unitSnapshot = readStr(fields, "unitSnapshot").ifEmpty { "pcs" },
                unitPurchasePricePaise = readLong(fields, "unitPurchasePricePaise", 0L),
                unitSellingPricePaise = readLong(fields, "unitSellingPricePaise", 0L),
                totalCostPaise = readLong(fields, "totalCostPaise", 0L),
                totalRevenuePaise = readLong(fields, "totalRevenuePaise", 0L),
                totalProfitPaise = readLong(fields, "totalProfitPaise", 0L),
                gainMarkupPercent = readDouble(fields, "gainMarkupPercent", 0.0),
                grossMarginPercent = readDouble(fields, "grossMarginPercent", 0.0),
                soldAt = soldAt,
                yearInt = cal.get(Calendar.YEAR),
                monthInt = cal.get(Calendar.MONTH) + 1,
                dayInt = cal.get(Calendar.DAY_OF_MONTH),
                isReturned = readBool(fields, "isReturned", false),
                updatedAt = readLong(fields, "updatedAt", soldAt),
                syncStatus = SyncState.SYNCED
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readStr(fields: JSONObject, key: String): String =
        fields.optJSONObject(key)?.optString("stringValue", "").orEmpty()

    private fun readLong(fields: JSONObject, key: String, defaultVal: Long): Long =
        fields.optJSONObject(key)?.optString("integerValue")?.toLongOrNull() ?: defaultVal

    private fun readDouble(fields: JSONObject, key: String, defaultVal: Double): Double {
        val f = fields.optJSONObject(key) ?: return defaultVal
        if (f.has("doubleValue")) return f.optDouble("doubleValue", defaultVal)
        if (f.has("integerValue")) return f.optString("integerValue")?.toDoubleOrNull() ?: defaultVal
        return defaultVal
    }

    private fun readBool(fields: JSONObject, key: String, defaultVal: Boolean): Boolean =
        fields.optJSONObject(key)?.optBoolean("booleanValue", defaultVal) ?: defaultVal

    private fun strVal(v: String) = JSONObject().put("stringValue", v)
    private fun intVal(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun doubleVal(v: Double) = JSONObject().put("doubleValue", v)
    private fun boolVal(v: Boolean) = JSONObject().put("booleanValue", v)
}
