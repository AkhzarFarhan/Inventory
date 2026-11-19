package com.shopledger.pos.data.command

import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Imports v1/v2 JSON backups by MERGING: rows whose id already exists are skipped. Stock and
 * balances arrive as OPENING ledger rows, so invariants hold. Re-importing the same file is a no-op.
 */
class LegacyJsonImporter(private val db: LpsDatabase, private val env: CommandEnvironment) {
    data class Report(val items: Int, val variants: Int, val parties: Int, val sales: Int)

    suspend fun import(json: String): Report {
        val root = JSONObject(json)
        val writer = LedgerWriter(db, env)
        val inventory = db.inventoryDao()
        val ledger = db.shopPartyAndLedgerDao()
        return writer.runIdempotent("legacy-import-${sha256(json)}", "LEGACY_IMPORT", replay = { Report(0, 0, 0, 0) }) {
            val device = writer.localDevice()
            val shopId = env.shopId()
            val now = env.now()
            val itemIds = inventory.getAllRawItems().map { it.id }.toHashSet()
            val variantIds = inventory.getAllRawVariants().map { it.id }.toHashSet()
            val partyIds = ledger.getAllRawParties().map { it.id }.toHashSet()
            var items = 0
            var variants = 0
            var parties = 0

            root.optJSONArray("items").forEachObject { o ->
                val id = o.getString("id")
                if (id in itemIds) return@forEachObject
                val name = o.getString("displayName").trim()
                inventory.insertItem(
                    InventoryItemEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        displayName = name,
                        sortKey = o.optString("sortKey", name.uppercase(Locale.ROOT)),
                        category = o.optString("category", "Uncategorized"),
                        createdAt = o.optLong("createdAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                itemIds += id
                writer.enqueue(OutboxDocType.ITEM, id, OutboxOp.UPSERT)
                items++
            }

            root.optJSONArray("variants").forEachObject { o ->
                val id = o.getString("id")
                val itemId = o.getString("itemId")
                if (id in variantIds || itemId !in itemIds) return@forEachObject
                inventory.insertVariant(
                    ItemVariantEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        itemId = itemId,
                        skuCode = o.optString("skuCode", ""),
                        barcode = o.optString("barcode", ""),
                        sizeLabel = o.optString("sizeLabel", ""),
                        materialLabel = o.optString("materialLabel", ""),
                        colorFinishLabel = o.optString("colorFinishLabel", ""),
                        unit = o.optString("unit", "pcs"),
                        purchasePricePaise = o.getLong("purchasePricePaise"),
                        sellingPricePaise = o.getLong("sellingPricePaise"),
                        gstRateBps = o.optInt("gstRateBps", 0),
                        stockMilliUnits = 0L,
                        lowStockMilliUnits = (o.optDouble("lowStockThreshold", 5.0) * 1000.0).roundToLong(),
                        createdAt = o.optLong("updatedAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                variantIds += id
                writer.enqueue(OutboxDocType.VARIANT, id, OutboxOp.UPSERT)
                val stockMilli = (o.optDouble("stockQuantity", 0.0) * 1000.0).roundToLong()
                if (stockMilli != 0L) {
                    writer.appendMovement(id, stockMilli, MovementType.OPENING, itemId, "Imported opening stock", device, enqueue = true)
                }
                variants++
            }

            root.optJSONArray("parties").forEachObject { o ->
                val id = o.getString("id")
                if (id in partyIds) return@forEachObject
                ledger.insertParty(
                    PartyEntity(
                        id = id,
                        shopId = o.optString("shopId", shopId),
                        partyType = o.optString("partyType", "CUSTOMER"),
                        name = o.getString("name"),
                        phone = o.optString("phone", ""),
                        gstin = o.optString("gstin", ""),
                        currentBalancePaise = 0L,
                        createdAt = o.optLong("updatedAt", now),
                        updatedAt = o.optLong("updatedAt", now),
                        isDeleted = o.optBoolean("isDeleted", false),
                        createdByUid = env.actor().uid,
                        deviceId = device.deviceId
                    )
                )
                partyIds += id
                writer.enqueue(OutboxDocType.PARTY, id, OutboxOp.UPSERT)
                val balance = o.optLong("currentBalancePaise", 0L)
                if (balance != 0L) {
                    writer.appendLedgerEntry(id, balance, LedgerEntryType.OPENING, "CASH", id, "Imported balance", device, enqueue = true)
                }
                parties++
            }

            val sales = importSales(root.optJSONArray("sales"), shopId, now)
            Report(items, variants, parties, sales) to "imported"
        }
    }

    /** INTERIM (Task 3): writes the v2 sale_records table. Task 4 rewrites this to build immutable invoices. */
    private suspend fun importSales(sales: JSONArray?, shopId: String, now: Long): Int {
        val saleDao = db.saleDao()
        val existing = saleDao.getAllRawSales().map { it.id }.toHashSet()
        val rows = ArrayList<SaleRecordEntity>()
        sales.forEachObject { o ->
            val id = o.getString("id")
            if (id in existing) return@forEachObject
            val revenue = o.getLong("totalRevenuePaise")
            rows += SaleRecordEntity(
                id = id,
                shopId = o.optString("shopId", shopId),
                receiptNumber = o.optString("receiptNumber", "IMPORTED"),
                buyerName = o.optString("buyerName", ""),
                buyerPhone = o.optString("buyerPhone", ""),
                itemId = o.getString("itemId"),
                variantId = o.getString("variantId"),
                itemNameSnapshot = o.getString("itemNameSnapshot"),
                variantSummarySnapshot = o.optString("variantSummarySnapshot", "Standard"),
                quantitySold = o.getDouble("quantitySold"),
                unitSnapshot = o.optString("unitSnapshot", "pcs"),
                unitPurchasePricePaise = o.getLong("unitPurchasePricePaise"),
                unitSellingPricePaise = o.getLong("unitSellingPricePaise"),
                totalCostPaise = o.optLong("totalCostPaise", 0L),
                totalRevenuePaise = revenue,
                totalProfitPaise = o.optLong("totalProfitPaise", 0L),
                gainMarkupPercent = o.optDouble("gainMarkupPercent", 0.0),
                grossMarginPercent = o.optDouble("grossMarginPercent", 0.0),
                paymentMode = o.optString("paymentMode", "CASH"),
                soldAt = o.optLong("soldAt", now),
                yearInt = o.optInt("yearInt", 2026),
                monthInt = o.optInt("monthInt", 1),
                dayInt = o.optInt("dayInt", 1),
                isReturned = o.optBoolean("isReturned", false)
            )
        }
        if (rows.isNotEmpty()) saleDao.insertSales(rows)
        return rows.size
    }

    private inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) block(getJSONObject(i))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
