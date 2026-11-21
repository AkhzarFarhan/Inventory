package com.shopledger.pos.data.command

import com.shopledger.pos.R
import com.shopledger.pos.core.ledger.CalendarParts
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.PurchaseBillEntity
import com.shopledger.pos.data.local.entity.PurchaseBillLineEntity
import java.util.Locale

data class StockInRow(
    /** null = create a new item. */
    val existingVariantId: String?,
    /** Required when [existingVariantId] is null. */
    val newItemName: String = "",
    val newCategory: String = "Uncategorized",
    val unit: String = "pcs",
    val qtyMilli: Long,
    /** Updates the variant's cost when different. */
    val costPaise: Long,
    /** Required for new items; ignored for existing ones. */
    val sellPaise: Long?
)

data class StockInRequest(
    val actionId: String,
    val rows: List<StockInRow>,
    val supplierPartyId: String? = null,
    /** Creates a SUPPLIER party when set and no id is given. If both an id and a name are given, the id wins and the name is ignored. */
    val newSupplierName: String? = null,
    val billTotalPaise: Long = 0L,
    val amountPaidPaise: Long = 0L,
    /** "CASH" or "UPI": how the paid-now part was paid. Ignored when amountPaidPaise == 0. */
    val paidMode: String = "CASH",
    /** Distributor's bill/invoice number, optional. */
    val billNumber: String = ""
)

data class StockInResult(
    val purchaseId: String,
    val createdItems: Int,
    val restockedItems: Int,
    val totalQtyMilli: Long,
    val supplierDuePaise: Long,
    val replayed: Boolean
)

object StockInStep {
    const val SUPPLIER = "SUPPLIER"
    const val ITEMS = "ITEMS"
    const val COSTS = "COSTS"
    const val MOVEMENTS = "MOVEMENTS"
    const val PURCHASE_BILL = "PURCHASE_BILL"
    const val LEDGER = "LEDGER"
    val ALL = listOf(SUPPLIER, ITEMS, COSTS, MOVEMENTS, PURCHASE_BILL, LEDGER)
}

/** Saves a whole scanned stock list (new items, restocks, cost updates, supplier bill) in one transaction. */
class StockInCommands(
    private val db: LpsDatabase,
    private val env: CommandEnvironment,
    private val faults: FaultInjector = FaultInjector.NONE
) {
    private val writer = LedgerWriter(db, env)
    private val inventory = db.inventoryDao()
    private val parties = db.shopPartyAndLedgerDao()
    private val purchases = db.purchaseDao()
    private val catalog = CatalogCommands(db, env)
    private val khata = KhataCommands(db, env)

    suspend fun receiveScannedStock(request: StockInRequest): StockInResult =
        writer.runIdempotent(request.actionId, "RECEIVE_SCANNED_STOCK", replay = { ref -> replayResult(ref) }) {
            validate(request)
            if (env.actor().role == Role.STAFF) {
                for (row in request.rows) {
                    val id = row.existingVariantId ?: continue
                    val variant = inventory.getVariantById(id)?.takeUnless { it.isDeleted } ?: continue
                    if (variant.purchasePricePaise != row.costPaise) {
                        throw CommandException.PermissionDenied(R.string.msg_owner_only_cost)
                    }
                }
            }
            val device = writer.localDevice()
            val purchaseId = env.newId()

            // Supplier: existing, or a new SUPPLIER party.
            val newSupplier = request.newSupplierName?.trim().orEmpty()
            val supplierId: String? = when {
                request.supplierPartyId != null -> {
                    val party = parties.getPartyById(request.supplierPartyId)?.takeUnless { it.isDeleted }
                        ?: throw CommandException.NotFound(R.string.msg_not_found_supplier)
                    if (party.partyType != "SUPPLIER" && party.partyType != "BOTH") {
                        throw CommandException.Validation(R.string.msg_choose_supplier)
                    }
                    request.supplierPartyId
                }
                newSupplier.isNotEmpty() -> khata.insertParty("SUPPLIER", newSupplier, "", "", device)
                else -> null
            }
            faults.at(StockInStep.SUPPLIER)

            // Resolve every row to a variant id, creating new items as needed.
            var created = 0
            val targets = request.rows.map { row ->
                if (row.existingVariantId != null) {
                    val variant = inventory.getVariantById(row.existingVariantId)?.takeUnless { it.isDeleted }
                        ?: throw CommandException.NotFound(R.string.msg_not_found_item)
                    variant.id
                } else {
                    val itemId = catalog.insertItemWithVariants(
                        row.newItemName.trim(),
                        row.newCategory,
                        listOf(VariantSpec(unit = row.unit, purchasePricePaise = row.costPaise, sellingPricePaise = row.sellPaise!!)),
                        isDemo = false,
                        device = device
                    )
                    created++
                    inventory.getVariantsForItem(itemId).single().id
                }
            }
            faults.at(StockInStep.ITEMS)

            // Cost changes on restocked variants.
            request.rows.forEachIndexed { index, row ->
                if (row.existingVariantId == null) return@forEachIndexed
                val variant = inventory.getVariantById(targets[index])!!
                if (variant.purchasePricePaise != row.costPaise) {
                    inventory.updateVariant(variant.copy(purchasePricePaise = row.costPaise, updatedAt = env.now()))
                    if (!variant.isDemo) writer.enqueue(OutboxDocType.VARIANT, variant.id, OutboxOp.UPSERT)
                }
            }
            faults.at(StockInStep.COSTS)

            request.rows.forEachIndexed { index, row ->
                val variant = inventory.getVariantById(targets[index])!!
                writer.appendMovement(
                    variant.id, row.qtyMilli, MovementType.PURCHASE, purchaseId,
                    "Stock in", device, enqueue = !variant.isDemo
                )
            }
            faults.at(StockInStep.MOVEMENTS)

            val parts = CalendarParts.of(env.now())
            val lines = request.rows.mapIndexed { index, row ->
                val variant = inventory.getVariantById(targets[index])!!
                val itemName = inventory.getItemById(variant.itemId)?.displayName.orEmpty()
                PurchaseBillLineEntity(
                    id = env.newId(),
                    shopId = env.shopId(),
                    purchaseId = purchaseId,
                    skuId = variant.id,
                    productId = variant.itemId,
                    itemNameSnapshot = itemName,
                    variantSummarySnapshot = variant.buildVariantSummary(),
                    unitSnapshot = variant.unit,
                    qtyMilliUnits = row.qtyMilli,
                    unitCostPaise = row.costPaise,
                    lineTotalPaise = (row.qtyMilli * row.costPaise + 500) / 1000
                )
            }
            val linesTotal = lines.sumOf { it.lineTotalPaise }
            val total = if (request.billTotalPaise > 0L) request.billTotalPaise else linesTotal
            val paid = request.amountPaidPaise.coerceAtMost(total)
            val paidModeUpper = request.paidMode.trim().uppercase(Locale.ROOT)
            val supplierName = supplierId?.let { parties.getPartyById(it)?.name }.orEmpty()
            purchases.insertBill(
                PurchaseBillEntity(
                    id = purchaseId,
                    shopId = env.shopId(),
                    supplierPartyId = supplierId,
                    supplierNameSnapshot = supplierName,
                    billNumber = request.billNumber.trim(),
                    totalPaise = total,
                    linesTotalPaise = linesTotal,
                    paidPaise = paid,
                    duePaise = total - paid,
                    paymentMode = if (paid > 0L) paidModeUpper else "CREDIT",
                    lineCount = lines.size,
                    createdAt = env.now(),
                    yearInt = parts.year,
                    monthInt = parts.month,
                    dayInt = parts.day,
                    isLegacy = false,
                    deviceId = device.deviceId,
                    createdByUid = env.actor().uid
                )
            )
            purchases.insertLines(lines)
            writer.enqueue(OutboxDocType.PURCHASE, purchaseId, OutboxOp.APPEND)
            faults.at(StockInStep.PURCHASE_BILL)

            var recordedDue = 0L
            if (supplierId != null) {
                if (total > 0L) {
                    writer.appendLedgerEntry(
                        supplierId, -total, LedgerEntryType.CREDIT_BILL, "CREDIT", purchaseId,
                        "Purchase bill", device, enqueue = true
                    )
                }
                if (paid > 0L) {
                    writer.appendLedgerEntry(
                        supplierId, paid, LedgerEntryType.PAYMENT_OUT, paidModeUpper, purchaseId,
                        "Paid with bill", device, enqueue = true
                    )
                }
                recordedDue = total - paid
            }
            faults.at(StockInStep.LEDGER)

            val restocked = request.rows.size - created
            val result = StockInResult(
                purchaseId, created, restocked, request.rows.sumOf { it.qtyMilli }, recordedDue, replayed = false
            )
            result to listOf(purchaseId, created, restocked, result.totalQtyMilli, recordedDue).joinToString("|")
        }

    private fun replayResult(ref: String): StockInResult {
        val (purchaseId, created, restocked, totalQty, due) = ref.split("|")
        return StockInResult(
            purchaseId = purchaseId,
            createdItems = created.toInt(),
            restockedItems = restocked.toInt(),
            totalQtyMilli = totalQty.toLong(),
            supplierDuePaise = due.toLong(),
            replayed = true
        )
    }

    private fun validate(request: StockInRequest) {
        if (request.rows.isEmpty()) throw CommandException.Validation(R.string.msg_add_one_item)
        val existing = request.rows.mapNotNull { it.existingVariantId }
        if (existing.toSet().size != existing.size) throw CommandException.Validation(R.string.msg_item_once)
        for (row in request.rows) {
            if (row.qtyMilli <= 0L) throw CommandException.Validation(R.string.msg_qty_positive)
            if (row.costPaise < 0L) throw CommandException.Validation(R.string.msg_price_negative)
            if (row.existingVariantId == null) {
                if (row.newItemName.isBlank()) throw CommandException.Validation(R.string.msg_item_name_required)
                val sell = row.sellPaise ?: throw CommandException.Validation(R.string.msg_sell_price_required)
                if (sell < 0L) throw CommandException.Validation(R.string.msg_price_negative)
            }
        }
        if (request.billTotalPaise < 0L || request.amountPaidPaise < 0L || request.amountPaidPaise > request.billTotalPaise) {
            throw CommandException.Validation(R.string.msg_paid_range)
        }
        val hasSupplier = request.supplierPartyId != null || !request.newSupplierName.isNullOrBlank()
        if (request.billTotalPaise > request.amountPaidPaise && !hasSupplier) {
            throw CommandException.Validation(R.string.msg_supplier_name_for_due)
        }
        val paidMode = request.paidMode.trim().uppercase(Locale.ROOT)
        if (paidMode != "CASH" && paidMode != "UPI") {
            throw CommandException.Validation(R.string.msg_paid_mode_unknown)
        }
        if (request.billNumber.trim().length > 40) {
            throw CommandException.Validation(R.string.msg_bill_number_too_long)
        }
    }
}
