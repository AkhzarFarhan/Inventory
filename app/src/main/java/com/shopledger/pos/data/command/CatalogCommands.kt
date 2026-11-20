package com.shopledger.pos.data.command

import com.shopledger.pos.R
import com.shopledger.pos.core.nlp.SmartItemMatcher
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import java.util.Locale

data class VariantSpec(
    val sizeLabel: String = "",
    val materialLabel: String = "",
    val colorFinishLabel: String = "",
    val unit: String = "pcs",
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val mrpPaise: Long = 0L,
    val openingStockMilli: Long = 0L,
    val lowStockMilli: Long = 5_000L,
    val barcode: String = "",
    val skuCode: String = "",
    val gstRateBps: Int = 0
)

data class VariantEdit(
    val sizeLabel: String,
    val materialLabel: String,
    val colorFinishLabel: String,
    val unit: String,
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val lowStockMilli: Long,
    val barcode: String,
    val skuCode: String,
    val gstRateBps: Int,
    /** Non-null: record an ADJUSTMENT so stock becomes exactly this (target − current, read in-transaction). */
    val targetStockMilli: Long? = null
)

data class PurchaseLine(val variantId: String, val qtyMilli: Long)

class CatalogCommands(private val db: LpsDatabase, private val env: CommandEnvironment) {
    private val writer = LedgerWriter(db, env)
    private val inventory = db.inventoryDao()
    private val parties = db.shopPartyAndLedgerDao()

    suspend fun createItem(
        actionId: String,
        displayName: String,
        category: String,
        variants: List<VariantSpec>,
        isDemo: Boolean = false
    ): String = writer.runIdempotent(actionId, "CREATE_ITEM", replay = { it }) {
        val name = displayName.trim()
        if (name.isEmpty()) throw CommandException.Validation(R.string.msg_item_name_required)
        if (variants.isEmpty()) throw CommandException.Validation(R.string.msg_add_variant)
        variants.forEach(::validate)
        val itemId = insertItemWithVariants(name, category, variants, isDemo, writer.localDevice())
        itemId to itemId
    }

    /** Shared by [createItem] and the stock-in command: inserts the item, its variants and their outbox rows. */
    internal suspend fun insertItemWithVariants(
        name: String,
        category: String,
        variants: List<VariantSpec>,
        isDemo: Boolean,
        device: DeviceEntity
    ): String {
        val now = env.now()
        val itemId = env.newId()
        inventory.insertItem(
            InventoryItemEntity(
                id = itemId,
                shopId = env.shopId(),
                displayName = name,
                sortKey = name.uppercase(Locale.ROOT),
                category = category.trim().ifEmpty { "Uncategorized" },
                createdAt = now,
                updatedAt = now,
                isDemo = isDemo,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        if (!isDemo) writer.enqueue(OutboxDocType.ITEM, itemId, OutboxOp.UPSERT)
        variants.forEach { insertVariant(itemId, it, device, isDemo) }
        return itemId
    }

    suspend fun addVariant(
        actionId: String,
        itemId: String,
        spec: VariantSpec,
        renameTo: String? = null,
        recategorizeTo: String? = null
    ): String = writer.runIdempotent(actionId, "ADD_VARIANT", replay = { it }) {
        val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound(R.string.msg_not_found_item)
        validate(spec)
        val device = writer.localDevice()
        val newName = renameTo?.trim()?.takeIf { it.isNotEmpty() }
        val newCategory = recategorizeTo?.trim()?.takeIf { it.isNotEmpty() }
        if (newName != null || newCategory != null) {
            val name = newName ?: item.displayName
            inventory.updateItem(
                item.copy(
                    displayName = name,
                    sortKey = name.uppercase(Locale.ROOT),
                    category = newCategory ?: item.category,
                    updatedAt = env.now()
                )
            )
            if (!item.isDemo) writer.enqueue(OutboxDocType.ITEM, item.id, OutboxOp.UPSERT)
        }
        val variantId = insertVariant(item.id, spec, device, item.isDemo)
        variantId to variantId
    }

    suspend fun editItem(
        actionId: String,
        itemId: String,
        expectedItemUpdatedAt: Long,
        newDisplayName: String,
        newCategory: String,
        variantId: String,
        expectedVariantUpdatedAt: Long,
        edit: VariantEdit
    ) {
        writer.runIdempotent(actionId, "EDIT_ITEM", replay = { }) {
            val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound(R.string.msg_not_found_item)
            val variant = inventory.getVariantById(variantId)?.takeIf { it.itemId == itemId && !it.isDeleted }
                ?: throw CommandException.NotFound(R.string.msg_not_found_item)
            if (item.updatedAt != expectedItemUpdatedAt) throw CommandException.StaleEdit.item(item.displayName)
            if (variant.updatedAt != expectedVariantUpdatedAt) {
                throw CommandException.StaleEdit.item(item.displayName)
            }
            if (env.actor().role == Role.STAFF && edit.purchasePricePaise != variant.purchasePricePaise) {
                throw CommandException.PermissionDenied(R.string.msg_owner_only_cost)
            }
            validatePrices(edit.purchasePricePaise, edit.sellingPricePaise, edit.lowStockMilli)
            val name = newDisplayName.trim()
            if (name.isEmpty()) throw CommandException.Validation(R.string.msg_item_name_required)
            val device = writer.localDevice()
            val now = env.now()
            inventory.updateItem(
                item.copy(
                    displayName = name,
                    sortKey = name.uppercase(Locale.ROOT),
                    category = newCategory.trim().ifEmpty { "Uncategorized" },
                    updatedAt = now
                )
            )
            inventory.updateVariant(
                variant.copy(
                    sizeLabel = SmartItemMatcher.normalizeSizeString(edit.sizeLabel),
                    materialLabel = SmartItemMatcher.normalizeMaterialString(edit.materialLabel),
                    colorFinishLabel = SmartItemMatcher.normalizeColorString(edit.colorFinishLabel),
                    unit = edit.unit.trim().ifEmpty { "pcs" },
                    purchasePricePaise = edit.purchasePricePaise,
                    sellingPricePaise = edit.sellingPricePaise,
                    lowStockMilliUnits = edit.lowStockMilli,
                    barcode = edit.barcode.trim(),
                    skuCode = edit.skuCode.trim(),
                    gstRateBps = edit.gstRateBps,
                    updatedAt = now
                )
            )
            if (!item.isDemo) {
                writer.enqueue(OutboxDocType.ITEM, item.id, OutboxOp.UPSERT)
                writer.enqueue(OutboxDocType.VARIANT, variant.id, OutboxOp.UPSERT)
            }
            val target = edit.targetStockMilli
            if (target != null) {
                if (target < 0L) throw CommandException.Validation(R.string.msg_stock_negative)
                val current = inventory.getVariantById(variantId)!!.stockMilliUnits
                val delta = target - current
                if (delta != 0L) {
                    writer.appendMovement(
                        variantId, delta, MovementType.ADJUSTMENT, item.id,
                        "Stock counted: ${target / 1000.0}", device, enqueue = !item.isDemo
                    )
                }
            }
            Unit to itemId
        }
    }

    /** Deletes one variant, or the whole item when [variantId] is null or it is the last active variant. */
    suspend fun deleteItem(actionId: String, itemId: String, variantId: String?) {
        writer.runIdempotent(actionId, "DELETE_ITEM", replay = { }) {
            if (env.actor().role == Role.STAFF) throw CommandException.PermissionDenied(R.string.msg_owner_only_delete)
            val item = inventory.getItemById(itemId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound(R.string.msg_not_found_item)
            val now = env.now()
            val active = inventory.getVariantsForItem(itemId).filter { !it.isDeleted }
            if (variantId == null || active.size <= 1) {
                inventory.softDeleteItem(itemId, now)
                inventory.softDeleteVariantsForItem(itemId, now)
                if (!item.isDemo) {
                    writer.enqueue(OutboxDocType.ITEM, itemId, OutboxOp.UPSERT)
                    active.forEach { writer.enqueue(OutboxDocType.VARIANT, it.id, OutboxOp.UPSERT) }
                }
            } else {
                if (active.none { it.id == variantId }) throw CommandException.NotFound(R.string.msg_not_found_item)
                inventory.softDeleteSingleVariant(variantId, now)
                if (!item.isDemo) writer.enqueue(OutboxDocType.VARIANT, variantId, OutboxOp.UPSERT)
            }
            Unit to itemId
        }
    }

    /** Give exactly one of [deltaMilli] (a correction) or [targetMilli] (a physical count). Returns new stock. */
    suspend fun adjustStock(
        actionId: String,
        variantId: String,
        deltaMilli: Long? = null,
        targetMilli: Long? = null,
        reason: String
    ): Long = writer.runIdempotent(
        actionId, "ADJUST_STOCK",
        replay = { ref -> inventory.getVariantById(ref)?.stockMilliUnits ?: 0L }
    ) {
        if ((deltaMilli == null) == (targetMilli == null)) {
            throw CommandException.Validation(R.string.msg_stock_change_or_count)
        }
        val variant = inventory.getVariantById(variantId)?.takeUnless { it.isDeleted }
            ?: throw CommandException.NotFound(R.string.msg_not_found_item)
        if (targetMilli != null && targetMilli < 0L) throw CommandException.Validation(R.string.msg_stock_negative)
        val device = writer.localDevice()
        val delta = deltaMilli ?: (targetMilli!! - variant.stockMilliUnits)
        if (delta != 0L) {
            writer.appendMovement(
                variant.id, delta,
                if (targetMilli != null) MovementType.ADJUSTMENT else MovementType.CORRECTION,
                variant.itemId, reason.trim().ifEmpty { "Stock adjustment" }, device, enqueue = !variant.isDemo
            )
        }
        inventory.getVariantById(variant.id)!!.stockMilliUnits to variant.id
    }

    /** Stock received from a supplier. An unpaid remainder becomes a payable (negative supplier balance). */
    suspend fun receivePurchase(
        actionId: String,
        lines: List<PurchaseLine>,
        supplierPartyId: String? = null,
        billTotalPaise: Long = 0L,
        amountPaidPaise: Long = 0L,
        note: String = ""
    ): String = writer.runIdempotent(actionId, "RECEIVE_PURCHASE", replay = { it }) {
        if (lines.isEmpty()) throw CommandException.Validation(R.string.msg_add_one_item)
        if (lines.map { it.variantId }.toSet().size != lines.size) {
            throw CommandException.Validation(R.string.msg_item_once)
        }
        if (billTotalPaise < 0L || amountPaidPaise < 0L || amountPaidPaise > billTotalPaise) {
            throw CommandException.Validation(R.string.msg_paid_range)
        }
        val device = writer.localDevice()
        val purchaseId = env.newId()
        for (line in lines) {
            if (line.qtyMilli <= 0L) throw CommandException.Validation(R.string.msg_qty_positive)
            val variant = inventory.getVariantById(line.variantId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound(R.string.msg_not_found_item)
            writer.appendMovement(
                variant.id, line.qtyMilli, MovementType.PURCHASE, purchaseId,
                note.trim().ifEmpty { "Purchase received" }, device, enqueue = !variant.isDemo
            )
        }
        val due = billTotalPaise - amountPaidPaise
        if (supplierPartyId != null && due > 0L) {
            parties.getPartyById(supplierPartyId)?.takeUnless { it.isDeleted }
                ?: throw CommandException.NotFound(R.string.msg_not_found_supplier)
            writer.appendLedgerEntry(
                supplierPartyId, -due, LedgerEntryType.CREDIT_BILL, "CREDIT", purchaseId,
                "Purchase bill", device, enqueue = true
            )
        }
        purchaseId to purchaseId
    }

    private suspend fun insertVariant(itemId: String, spec: VariantSpec, device: DeviceEntity, isDemo: Boolean): String {
        val variantId = env.newId()
        val now = env.now()
        inventory.insertVariant(
            ItemVariantEntity(
                id = variantId,
                shopId = env.shopId(),
                itemId = itemId,
                skuCode = spec.skuCode.trim(),
                barcode = spec.barcode.trim(),
                sizeLabel = SmartItemMatcher.normalizeSizeString(spec.sizeLabel),
                materialLabel = SmartItemMatcher.normalizeMaterialString(spec.materialLabel),
                colorFinishLabel = SmartItemMatcher.normalizeColorString(spec.colorFinishLabel),
                unit = spec.unit.trim().ifEmpty { "pcs" },
                mrpPaise = spec.mrpPaise,
                purchasePricePaise = spec.purchasePricePaise,
                sellingPricePaise = spec.sellingPricePaise,
                gstRateBps = spec.gstRateBps,
                stockMilliUnits = 0L,
                lowStockMilliUnits = spec.lowStockMilli,
                createdAt = now,
                updatedAt = now,
                isDemo = isDemo,
                createdByUid = env.actor().uid,
                deviceId = device.deviceId
            )
        )
        if (!isDemo) writer.enqueue(OutboxDocType.VARIANT, variantId, OutboxOp.UPSERT)
        if (spec.openingStockMilli != 0L) {
            writer.appendMovement(variantId, spec.openingStockMilli, MovementType.OPENING, itemId, "Opening stock", device, enqueue = !isDemo)
        }
        return variantId
    }

    private fun validate(spec: VariantSpec) {
        validatePrices(spec.purchasePricePaise, spec.sellingPricePaise, spec.lowStockMilli)
        if (spec.openingStockMilli < 0L) throw CommandException.Validation(R.string.msg_opening_stock_negative)
    }

    private fun validatePrices(costPaise: Long, sellPaise: Long, lowStockMilli: Long) {
        if (costPaise < 0L || sellPaise < 0L) throw CommandException.Validation(R.string.msg_price_negative)
        if (lowStockMilli < 0L) throw CommandException.Validation(R.string.msg_low_stock_negative)
    }
}
