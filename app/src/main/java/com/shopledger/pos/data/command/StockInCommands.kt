package com.shopledger.pos.data.command

import com.shopledger.pos.data.local.LpsDatabase

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
    val amountPaidPaise: Long = 0L
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
    const val LEDGER = "LEDGER"
    val ALL = listOf(SUPPLIER, ITEMS, COSTS, MOVEMENTS, LEDGER)
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
                        throw CommandException.PermissionDenied("Only the owner can change the cost price")
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
                        ?: throw CommandException.NotFound("Supplier")
                    if (party.partyType != "SUPPLIER" && party.partyType != "BOTH") {
                        throw CommandException.Validation("Choose a supplier for this bill")
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
                        ?: throw CommandException.NotFound("Item variant")
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

            val due = request.billTotalPaise - request.amountPaidPaise
            var recordedDue = 0L
            if (supplierId != null && due > 0L) {
                writer.appendLedgerEntry(
                    supplierId, -due, LedgerEntryType.CREDIT_BILL, "CREDIT", purchaseId,
                    "Purchase bill", device, enqueue = true
                )
                recordedDue = due
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
        if (request.rows.isEmpty()) throw CommandException.Validation("Add at least one item")
        val existing = request.rows.mapNotNull { it.existingVariantId }
        if (existing.toSet().size != existing.size) throw CommandException.Validation("Each item can appear only once")
        for (row in request.rows) {
            if (row.qtyMilli <= 0L) throw CommandException.Validation("Quantity must be more than 0")
            if (row.costPaise < 0L) throw CommandException.Validation("Prices cannot be negative")
            if (row.existingVariantId == null) {
                if (row.newItemName.isBlank()) throw CommandException.Validation("Item name is required")
                val sell = row.sellPaise ?: throw CommandException.Validation("Selling price is required for new items")
                if (sell < 0L) throw CommandException.Validation("Prices cannot be negative")
            }
        }
        if (request.billTotalPaise < 0L || request.amountPaidPaise < 0L || request.amountPaidPaise > request.billTotalPaise) {
            throw CommandException.Validation("Paid amount must be between 0 and the bill total")
        }
        val hasSupplier = request.supplierPartyId != null || !request.newSupplierName.isNullOrBlank()
        if (request.billTotalPaise > request.amountPaidPaise && !hasSupplier) {
            throw CommandException.Validation("Add the supplier's name to record the unpaid amount")
        }
    }
}
