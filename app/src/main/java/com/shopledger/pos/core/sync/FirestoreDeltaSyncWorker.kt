package com.shopledger.pos.core.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncState
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
import kotlin.math.roundToLong

/**
 * Bidirectional Multi-Tenant Firebase Cloud Firestore Sync Worker:
 * 1. Pushes pending items, variants/SKUs, sales, parties, khata ledger entries, and additive
 *    stock movements in safe chunks of <=400 writes per commit request (under Firestore's 500 limit).
 * 2. Pulls remote collections and applies unseen remote stock movements additively inside a Room
 *    transaction so multi-device offline counters converge accurately without overwriting stock.
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
        val shopDao = db.shopPartyAndLedgerDao()

        val idToken = GoogleAuthManager.getValidFirebaseIdToken(applicationContext)
        val baseDocPath = "projects/${config.firebaseProjectId}/databases/(default)/documents/shops/${config.shopCollectionId}"
        val apiKeyParam = if (config.firebaseApiKey.isNotBlank()) "?key=${config.firebaseApiKey}" else ""
        val commitUrl = "https://firestore.googleapis.com/v1/projects/${config.firebaseProjectId}/databases/(default)/documents:commit$apiKeyParam"

        try {
            // =========================================================================
            // PHASE 1: PUSH LOCAL DIRTY RECORDS (CHUNKED IN <= 400 WRITES PER COMMIT)
            // =========================================================================
            val pendingItems = invDao.getPendingSyncItems()
            val pendingVariants = invDao.getPendingSyncVariants()
            val pendingSales = saleDao.getPendingSyncSales()
            val pendingParties = shopDao.getPendingSyncParties()
            val pendingLedger = shopDao.getPendingSyncLedgerEntries()
            val pendingMovements = shopDao.getPendingSyncMovements()

            val allWrites = ArrayList<JSONObject>()

            for (item in pendingItems) {
                val fields = JSONObject().apply {
                    put("shopId", strVal(item.shopId))
                    put("displayName", strVal(item.displayName))
                    put("sortKey", strVal(item.sortKey))
                    put("category", strVal(item.category))
                    put("defaultHsnCode", strVal(item.defaultHsnCode))
                    put("defaultGstRateBps", intVal(item.defaultGstRateBps.toLong()))
                    put("createdAt", intVal(item.createdAt))
                    put("updatedAt", intVal(item.updatedAt))
                    put("isDeleted", boolVal(item.isDeleted))
                }
                allWrites.add(
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
                    put("shopId", strVal(variant.shopId))
                    put("itemId", strVal(variant.itemId))
                    put("skuCode", strVal(variant.skuCode))
                    put("barcode", strVal(variant.barcode))
                    put("sizeLabel", strVal(variant.sizeLabel))
                    put("materialLabel", strVal(variant.materialLabel))
                    put("colorFinishLabel", strVal(variant.colorFinishLabel))
                    put("unit", strVal(variant.unit))
                    put("mrpPaise", intVal(variant.mrpPaise))
                    put("purchasePricePaise", intVal(variant.purchasePricePaise))
                    put("sellingPricePaise", intVal(variant.sellingPricePaise))
                    put("gstRateBps", intVal(variant.gstRateBps.toLong()))
                    put("stockQuantity", doubleVal(variant.stockQuantity))
                    put("lowStockThreshold", doubleVal(variant.lowStockThreshold))
                    put("stockMilliUnits", intVal(variant.stockMilliUnits))
                    put("lowStockMilliUnits", intVal(variant.lowStockMilliUnits))
                    put("updatedAt", intVal(variant.updatedAt))
                    put("isDeleted", boolVal(variant.isDeleted))
                }
                allWrites.add(
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
                    put("shopId", strVal(sale.shopId))
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
                    put("paymentMode", strVal(sale.paymentMode))
                    put("amountPaidPaise", intVal(sale.amountPaidPaise))
                    put("balanceDuePaise", intVal(sale.balanceDuePaise))
                    put("discountPaise", intVal(sale.discountPaise))
                    put("gstPaise", intVal(sale.gstPaise))
                    put("soldAt", intVal(sale.soldAt))
                    put("isReturned", boolVal(sale.isReturned))
                    put("updatedAt", intVal(sale.updatedAt))
                }
                allWrites.add(
                    JSONObject().put(
                        "update",
                        JSONObject()
                            .put("name", "$baseDocPath/sales/${sale.id}")
                            .put("fields", fields)
                    )
                )
            }

            for (party in pendingParties) {
                val fields = JSONObject().apply {
                    put("shopId", strVal(party.shopId))
                    put("partyType", strVal(party.partyType))
                    put("name", strVal(party.name))
                    put("phone", strVal(party.phone))
                    put("gstin", strVal(party.gstin))
                    put("billingAddress", strVal(party.billingAddress))
                    put("openingBalancePaise", intVal(party.openingBalancePaise))
                    put("currentBalancePaise", intVal(party.currentBalancePaise))
                    put("updatedAt", intVal(party.updatedAt))
                    put("isDeleted", boolVal(party.isDeleted))
                }
                allWrites.add(
                    JSONObject().put(
                        "update",
                        JSONObject()
                            .put("name", "$baseDocPath/parties/${party.id}")
                            .put("fields", fields)
                    )
                )
            }

            for (entry in pendingLedger) {
                val fields = JSONObject().apply {
                    put("shopId", strVal(entry.shopId))
                    put("partyId", strVal(entry.partyId))
                    put("refDocId", strVal(entry.refDocId))
                    put("entryType", strVal(entry.entryType))
                    put("amountDeltaPaise", intVal(entry.amountDeltaPaise))
                    put("paymentMode", strVal(entry.paymentMode))
                    put("note", strVal(entry.note))
                    put("createdAt", intVal(entry.createdAt))
                }
                allWrites.add(
                    JSONObject().put(
                        "update",
                        JSONObject()
                            .put("name", "$baseDocPath/party_ledger/${entry.entryId}")
                            .put("fields", fields)
                    )
                )
            }

            for (mov in pendingMovements) {
                val fields = JSONObject().apply {
                    put("shopId", strVal(mov.shopId))
                    put("skuId", strVal(mov.skuId))
                    put("deviceShortCode", strVal(mov.deviceShortCode))
                    put("deltaMilliUnits", intVal(mov.deltaMilliUnits))
                    put("movementType", strVal(mov.movementType))
                    put("refDocId", strVal(mov.refDocId))
                    put("note", strVal(mov.note))
                    put("createdAt", intVal(mov.createdAt))
                }
                allWrites.add(
                    JSONObject().put(
                        "update",
                        JSONObject()
                            .put("name", "$baseDocPath/stock_movements/${mov.movementId}")
                            .put("fields", fields)
                    )
                )
            }

            if (allWrites.isNotEmpty()) {
                for (chunk in allWrites.chunked(400)) {
                    val writesArray = JSONArray()
                    chunk.forEach { writesArray.put(it) }
                    val commitPayload = JSONObject().put("writes", writesArray).toString()

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
                    }
                }
                if (pendingItems.isNotEmpty()) invDao.markItemsSynced(pendingItems.map { it.id })
                if (pendingVariants.isNotEmpty()) invDao.markVariantsSynced(pendingVariants.map { it.id })
                if (pendingSales.isNotEmpty()) saleDao.markSalesSynced(pendingSales.map { it.id })
                if (pendingParties.isNotEmpty()) shopDao.markPartiesSynced(pendingParties.map { it.id })
                if (pendingLedger.isNotEmpty()) shopDao.markLedgerEntriesSynced(pendingLedger.map { it.entryId })
                if (pendingMovements.isNotEmpty()) shopDao.markMovementsSynced(pendingMovements.map { it.movementId })
            }

            // =========================================================================
            // PHASE 2: PULL REMOTE ITEMS, VARIANTS, SALES, PARTIES & MOVEMENTS
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

            val remoteParties = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "parties",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteParty(doc) }.orEmpty()

            val remoteLedger = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "party_ledger",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteLedgerEntry(doc) }.orEmpty()

            val remoteMovements = fetchAllDocumentsInCollection(
                projectId = config.firebaseProjectId,
                shopCollectionId = config.shopCollectionId,
                subCollection = "stock_movements",
                apiKey = config.firebaseApiKey,
                idToken = idToken
            )?.mapNotNull { doc -> parseRemoteMovement(doc) }.orEmpty()

            if (remoteItems.isNotEmpty() || remoteVariants.isNotEmpty() || remoteSales.isNotEmpty() ||
                remoteParties.isNotEmpty() || remoteLedger.isNotEmpty() || remoteMovements.isNotEmpty()
            ) {
                val localItemsMap = invDao.getAllRawItems().associateBy { it.id }
                val localVariantsMap = invDao.getAllRawVariants().associateBy { it.id }
                val localSalesMap = saleDao.getAllRawSales().associateBy { it.id }
                val localPartiesMap = shopDao.getAllRawParties().associateBy { it.id }
                val existingMovementIds = shopDao.getAllMovementIds().toHashSet()

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
                val partiesToUpsert = remoteParties.filter { rem ->
                    val loc = localPartiesMap[rem.id]
                    loc == null || (loc.syncStatus != SyncState.PENDING_UPLOAD && rem.updatedAt >= loc.updatedAt)
                }
                val unseenMovements = remoteMovements.filter { rem ->
                    rem.movementId !in existingMovementIds
                }

                db.withTransaction {
                    if (remoteItems.isNotEmpty()) {
                        invDao.deleteDemoSeedVariants()
                        invDao.deleteDemoSeedItems()
                    }
                    if (itemsToUpsert.isNotEmpty()) invDao.upsertItems(itemsToUpsert)
                    if (variantsToUpsert.isNotEmpty()) invDao.upsertVariants(variantsToUpsert)
                    if (salesToUpsert.isNotEmpty()) saleDao.insertSales(salesToUpsert)
                    if (partiesToUpsert.isNotEmpty()) shopDao.upsertParties(partiesToUpsert)
                    if (remoteLedger.isNotEmpty()) shopDao.insertLedgerEntries(remoteLedger)

                    // Apply additive stock movements for variants that already existed locally
                    for (mov in unseenMovements) {
                        val rowId = shopDao.insertStockMovement(mov)
                        if (rowId != -1L && localVariantsMap.containsKey(mov.skuId) &&
                            variantsToUpsert.none { it.id == mov.skuId }
                        ) {
                            invDao.applyRemoteStockDeltaMilliUnits(mov.skuId, mov.deltaMilliUnits)
                        }
                    }
                }
            }

            val now = System.currentTimeMillis()
            SyncManager.updateLastSyncTimestamp(applicationContext, now)
            SyncManager.setSyncState(
                false,
                "Cloud Synced (${remoteItems.size} items)"
            )
            Result.success()
        } catch (_: Exception) {
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
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val displayName = readStr(fields, "displayName")
            if (displayName.isBlank()) return null

            InventoryItemEntity(
                id = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                displayName = displayName,
                sortKey = readStr(fields, "sortKey").ifEmpty { displayName.uppercase() },
                category = readStr(fields, "category").ifEmpty { "Uncategorized" },
                defaultHsnCode = readStr(fields, "defaultHsnCode"),
                defaultGstRateBps = readLong(fields, "defaultGstRateBps", 0L).toInt(),
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
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val itemId = readStr(fields, "itemId")
            if (itemId.isBlank()) return null

            val stockQty = readDouble(fields, "stockQuantity", 0.0)
            val lowAlert = readDouble(fields, "lowStockThreshold", 5.0)
            ItemVariantEntity(
                id = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                itemId = itemId,
                skuCode = readStr(fields, "skuCode"),
                barcode = readStr(fields, "barcode"),
                sizeLabel = readStr(fields, "sizeLabel"),
                materialLabel = readStr(fields, "materialLabel"),
                colorFinishLabel = readStr(fields, "colorFinishLabel"),
                unit = readStr(fields, "unit").ifEmpty { "pcs" },
                mrpPaise = readLong(fields, "mrpPaise", 0L),
                purchasePricePaise = readLong(fields, "purchasePricePaise", 0L),
                sellingPricePaise = readLong(fields, "sellingPricePaise", 0L),
                gstRateBps = readLong(fields, "gstRateBps", 0L).toInt(),
                stockQuantity = stockQty,
                lowStockThreshold = lowAlert,
                stockMilliUnits = readLong(fields, "stockMilliUnits", (stockQty * 1000.0).roundToLong()),
                lowStockMilliUnits = readLong(fields, "lowStockMilliUnits", (lowAlert * 1000.0).roundToLong()),
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
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val soldAt = readLong(fields, "soldAt", System.currentTimeMillis())
            val cal = Calendar.getInstance().apply { timeInMillis = soldAt }
            val totalRevenue = readLong(fields, "totalRevenuePaise", 0L)

            SaleRecordEntity(
                id = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                receiptNumber = readStr(fields, "receiptNumber").ifEmpty { "INV-0000" },
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
                totalRevenuePaise = totalRevenue,
                totalProfitPaise = readLong(fields, "totalProfitPaise", 0L),
                gainMarkupPercent = readDouble(fields, "gainMarkupPercent", 0.0),
                grossMarginPercent = readDouble(fields, "grossMarginPercent", 0.0),
                paymentMode = readStr(fields, "paymentMode").ifEmpty { "CASH" },
                amountPaidPaise = readLong(fields, "amountPaidPaise", totalRevenue),
                balanceDuePaise = readLong(fields, "balanceDuePaise", 0L),
                discountPaise = readLong(fields, "discountPaise", 0L),
                gstPaise = readLong(fields, "gstPaise", 0L),
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

    private fun parseRemoteParty(doc: JSONObject): PartyEntity? {
        return try {
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val name = readStr(fields, "name")
            if (name.isBlank()) return null
            PartyEntity(
                id = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                partyType = readStr(fields, "partyType").ifEmpty { "CUSTOMER" },
                name = name,
                phone = readStr(fields, "phone"),
                gstin = readStr(fields, "gstin"),
                billingAddress = readStr(fields, "billingAddress"),
                openingBalancePaise = readLong(fields, "openingBalancePaise", 0L),
                currentBalancePaise = readLong(fields, "currentBalancePaise", 0L),
                updatedAt = readLong(fields, "updatedAt", System.currentTimeMillis()),
                isDeleted = readBool(fields, "isDeleted", false),
                syncStatus = SyncState.SYNCED
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRemoteLedgerEntry(doc: JSONObject): PartyTransactionEntity? {
        return try {
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val partyId = readStr(fields, "partyId")
            if (partyId.isBlank()) return null
            PartyTransactionEntity(
                entryId = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                partyId = partyId,
                refDocId = readStr(fields, "refDocId"),
                entryType = readStr(fields, "entryType").ifEmpty { "CREDIT_BILL" },
                amountDeltaPaise = readLong(fields, "amountDeltaPaise", 0L),
                paymentMode = readStr(fields, "paymentMode").ifEmpty { "CASH" },
                note = readStr(fields, "note"),
                createdAt = readLong(fields, "createdAt", System.currentTimeMillis()),
                syncStatus = SyncState.SYNCED
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRemoteMovement(doc: JSONObject): StockMovementEntity? {
        return try {
            val id = doc.getString("name").substringAfterLast("/")
            val fields = doc.optJSONObject("fields") ?: return null
            val skuId = readStr(fields, "skuId")
            if (skuId.isBlank()) return null
            StockMovementEntity(
                movementId = id,
                shopId = readStr(fields, "shopId").ifEmpty { "default_shop" },
                skuId = skuId,
                deviceShortCode = readStr(fields, "deviceShortCode"),
                deltaMilliUnits = readLong(fields, "deltaMilliUnits", 0L),
                movementType = readStr(fields, "movementType").ifEmpty { "ADJUSTMENT" },
                refDocId = readStr(fields, "refDocId"),
                note = readStr(fields, "note"),
                createdAt = readLong(fields, "createdAt", System.currentTimeMillis()),
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
