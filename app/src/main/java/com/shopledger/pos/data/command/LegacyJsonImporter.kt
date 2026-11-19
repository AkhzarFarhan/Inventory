package com.shopledger.pos.data.command

import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.PartyEntity
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

            val sales = importSales(root.optJSONArray("sales"), writer, device, shopId, now)
            Report(items, variants, parties, sales) to "imported"
        }
    }

    /**
     * Groups legacy per-line sales into immutable invoices. No stock movements are written: the
     * exported stock is already net of these sales. Returned lines become a cash credit note.
     */
    private suspend fun importSales(
        sales: JSONArray?,
        writer: LedgerWriter,
        device: DeviceEntity,
        shopId: String,
        now: Long
    ): Int {
        val ledger = db.shopPartyAndLedgerDao()
        val existingLineIds = ledger.getAllInvoiceLines().map { it.id }.toHashSet()
        val takenNumbers = ledger.getInvoiceNumbers(shopId).toHashSet()
        val byReceipt = LinkedHashMap<String, MutableList<JSONObject>>()
        sales.forEachObject { o ->
            if (o.getString("id") !in existingLineIds) {
                byReceipt.getOrPut(o.optString("receiptNumber", "IMPORTED")) { ArrayList() }.add(o)
            }
        }
        var imported = 0
        for ((receipt, rows) in byReceipt) {
            val number = generateSequence(receipt) { "$it-I" }.first { it !in takenNumbers }
            takenNumbers += number
            val invoiceId = env.newId()
            val issuedAt = rows.maxOf { it.optLong("soldAt", now) }
            val parts = CalendarParts.of(issuedAt)
            val first = rows.first()
            val lines = rows.map { o ->
                InvoiceLineEntity(
                    id = o.getString("id"),
                    shopId = shopId,
                    invoiceId = invoiceId,
                    productId = o.getString("itemId"),
                    skuId = o.getString("variantId"),
                    itemNameSnapshot = o.getString("itemNameSnapshot"),
                    variantSummarySnapshot = o.optString("variantSummarySnapshot", "Standard"),
                    qtyMilliUnits = (o.getDouble("quantitySold") * 1000.0).roundToLong(),
                    unitSnapshot = o.optString("unitSnapshot", "pcs"),
                    unitCostPaise = o.getLong("unitPurchasePricePaise"),
                    unitSellPaise = o.getLong("unitSellingPricePaise"),
                    lineTotalPaise = o.getLong("totalRevenuePaise"),
                    lineProfitPaise = o.optLong("totalProfitPaise", 0L)
                )
            }
            val total = lines.sumOf { it.lineTotalPaise }
            fun header(id: String, docNumber: String, docType: String, originalId: String?, amount: Long) = InvoiceHeaderEntity(
                id = id, shopId = shopId, deviceId = device.deviceId, deviceShortCode = device.deviceCode,
                invoiceNumber = docNumber, docType = docType, originalInvoiceId = originalId,
                buyerName = first.optString("buyerName", ""), buyerPhone = first.optString("buyerPhone", ""),
                paymentMode = first.optString("paymentMode", "CASH"), subtotalPaise = amount, grandTotalPaise = amount,
                amountPaidPaise = amount, balanceDuePaise = 0L, issuedAt = issuedAt, yearInt = parts.year,
                monthInt = parts.month, dayInt = parts.day, createdByUid = env.actor().uid
            )
            ledger.insertInvoiceHeader(header(invoiceId, number, DocType.SALE, null, total))
            ledger.insertInvoiceLines(lines)
            writer.enqueue(OutboxDocType.INVOICE, invoiceId, OutboxOp.APPEND)

            val returnedIds = rows.filter { it.optBoolean("isReturned", false) }.map { it.getString("id") }.toSet()
            val returned = lines.filter { it.id in returnedIds }
            if (returned.isNotEmpty()) {
                val creditNoteId = env.newId()
                val creditLines = returned.map { l ->
                    l.copy(id = env.newId(), invoiceId = creditNoteId, originalLineId = l.id, qtyMilliUnits = -l.qtyMilliUnits,
                        lineTotalPaise = -l.lineTotalPaise, lineProfitPaise = -l.lineProfitPaise)
                }
                val creditNumber = generateSequence("$number-R") { "$it-R" }.first { it !in takenNumbers }
                takenNumbers += creditNumber
                ledger.insertInvoiceHeader(header(creditNoteId, creditNumber, DocType.CREDIT_NOTE, invoiceId, creditLines.sumOf { it.lineTotalPaise }))
                ledger.insertInvoiceLines(creditLines)
                writer.enqueue(OutboxDocType.INVOICE, creditNoteId, OutboxOp.APPEND)
            }
            imported += rows.size
        }
        return imported
    }

    private inline fun JSONArray?.forEachObject(block: (JSONObject) -> Unit) {
        if (this == null) return
        for (i in 0 until length()) block(getJSONObject(i))
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
