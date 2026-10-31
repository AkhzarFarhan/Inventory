package com.lpshardware.inventory.core.buyer

import androidx.compose.runtime.Immutable
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.model.CartLineItem
import com.lpshardware.inventory.data.model.InventoryItemWithVariants

@Immutable
data class BuyerOrderLineSnapshot(
    val itemId: String,
    val variantId: String,
    val itemName: String,
    val variantSummary: String,
    val quantity: Double,
    val unit: String,
    val unitPurchasePricePaise: Long,
    val unitSellingPricePaise: Long,
    val soldAt: Long,
    val receiptNumber: String
)

@Immutable
data class KnownBuyerProfile(
    val key: String,
    val buyerName: String,
    val buyerPhone: String,
    val totalDistinctOrders: Int,
    val totalSpentPaise: Long,
    val lastPurchaseAt: Long,
    val lastReceiptNumber: String,
    /** Items from the buyer's most recent receipt (filled on single-tap buyer selection) */
    val lastOrderItems: List<BuyerOrderLineSnapshot>,
    /** All distinct items/variants ever purchased by this buyer (most recent price/qty first) */
    val allDistinctPurchasedItems: List<BuyerOrderLineSnapshot>
)

object BuyerHistoryHelper {

    /**
     * Builds a list of known buyers and their order history from recorded sales.
     */
    fun extractBuyerProfiles(sales: List<SaleRecordEntity>): List<KnownBuyerProfile> {
        val validSales = sales.filter {
            !it.isReturned && (it.buyerPhone.isNotBlank() || it.buyerName.isNotBlank())
        }
        if (validSales.isEmpty()) return emptyList()

        // Group by normalized 10-digit phone if available, otherwise lowercase name
        val grouped = validSales.groupBy { sale ->
            val phoneDigits = sale.buyerPhone.filter { it.isDigit() }.takeLast(10)
            if (phoneDigits.length >= 6) {
                "phone_$phoneDigits"
            } else {
                "name_${sale.buyerName.trim().lowercase()}"
            }
        }

        return grouped.map { (key, buyerSales) ->
            val sortedDesc = buyerSales.sortedByDescending { it.soldAt }
            val latestSale = sortedDesc.first()

            val bestName = sortedDesc.firstOrNull { it.buyerName.isNotBlank() }?.buyerName.orEmpty()
            val rawBestPhone = sortedDesc.firstOrNull { it.buyerPhone.isNotBlank() }?.buyerPhone.orEmpty()
            val rawPhoneDigits = rawBestPhone.filter { it.isDigit() }
            val bestPhone = if (rawPhoneDigits.length >= 10) rawPhoneDigits.takeLast(10) else rawBestPhone

            val distinctReceipts = sortedDesc.map { it.receiptNumber }.distinct()
            val totalSpent = sortedDesc.sumOf { it.totalRevenuePaise }

            val lastReceiptNo = latestSale.receiptNumber
            val lastOrderLines = sortedDesc
                .filter { it.receiptNumber == lastReceiptNo }
                .map { it.toSnapshot() }

            val distinctItemsByVariant = LinkedHashMap<String, BuyerOrderLineSnapshot>()
            for (s in sortedDesc) {
                val vKey = "${s.itemId}_${s.variantId}_${s.itemNameSnapshot}_${s.variantSummarySnapshot}"
                if (!distinctItemsByVariant.containsKey(vKey)) {
                    distinctItemsByVariant[vKey] = s.toSnapshot()
                }
            }

            KnownBuyerProfile(
                key = key,
                buyerName = bestName,
                buyerPhone = bestPhone,
                totalDistinctOrders = distinctReceipts.size,
                totalSpentPaise = totalSpent,
                lastPurchaseAt = latestSale.soldAt,
                lastReceiptNumber = lastReceiptNo,
                lastOrderItems = lastOrderLines,
                allDistinctPurchasedItems = distinctItemsByVariant.values.toList()
            )
        }.sortedByDescending { it.lastPurchaseAt }
    }

    /**
     * Filters known buyers when the seller types either a name or WhatsApp number during a sale.
     */
    fun findMatchingBuyers(
        typedName: String,
        typedPhone: String,
        allProfiles: List<KnownBuyerProfile>
    ): List<KnownBuyerProfile> {
        val nameQ = typedName.trim().lowercase()
        val phoneQ = typedPhone.filter { it.isDigit() }

        if (nameQ.isEmpty() && phoneQ.isEmpty()) {
            return allProfiles.take(4)
        }

        return allProfiles.filter { profile ->
            val profileNameLower = profile.buyerName.lowercase()
            val profilePhoneDigits = profile.buyerPhone.filter { it.isDigit() }

            val matchesName = nameQ.isNotEmpty() && profileNameLower.contains(nameQ)
            val matchesPhone = phoneQ.isNotEmpty() && profilePhoneDigits.contains(phoneQ)

            matchesName || matchesPhone
        }.take(5)
    }

    /**
     * Converts a buyer's historical order lines into live [CartLineItem]s, resolving current stock
     * and latest purchase cost if the variant still exists in inventory.
     */
    fun mapSnapshotsToCartLineItems(
        snapshots: List<BuyerOrderLineSnapshot>,
        currentInventory: List<InventoryItemWithVariants>
    ): List<CartLineItem> {
        val variantLookup = HashMap<String, Pair<InventoryItemWithVariants, com.lpshardware.inventory.data.local.entity.ItemVariantEntity>>()
        for (itemWithVars in currentInventory) {
            for (v in itemWithVars.variants) {
                variantLookup[v.id] = itemWithVars to v
            }
        }

        return snapshots.map { snap ->
            val liveMatch = variantLookup[snap.variantId]
            if (liveMatch != null) {
                val (liveParent, liveVariant) = liveMatch
                CartLineItem(
                    itemId = liveParent.item.id,
                    variantId = liveVariant.id,
                    itemName = liveParent.item.displayName,
                    variantSummary = liveVariant.buildVariantSummary(),
                    unit = liveVariant.unit,
                    availableStock = liveVariant.stockQuantity,
                    unitPurchasePricePaise = liveVariant.purchasePricePaise,
                    defaultSellingPricePaise = liveVariant.sellingPricePaise,
                    customSellingPricePaise = snap.unitSellingPricePaise,
                    quantity = snap.quantity
                )
            } else {
                CartLineItem(
                    itemId = snap.itemId,
                    variantId = snap.variantId,
                    itemName = snap.itemName,
                    variantSummary = snap.variantSummary,
                    unit = snap.unit,
                    availableStock = snap.quantity,
                    unitPurchasePricePaise = snap.unitPurchasePricePaise,
                    defaultSellingPricePaise = snap.unitSellingPricePaise,
                    customSellingPricePaise = snap.unitSellingPricePaise,
                    quantity = snap.quantity
                )
            }
        }
    }

    private fun SaleRecordEntity.toSnapshot() = BuyerOrderLineSnapshot(
        itemId = itemId,
        variantId = variantId,
        itemName = itemNameSnapshot,
        variantSummary = variantSummarySnapshot,
        quantity = quantitySold,
        unit = unitSnapshot,
        unitPurchasePricePaise = unitPurchasePricePaise,
        unitSellingPricePaise = unitSellingPricePaise,
        soldAt = soldAt,
        receiptNumber = receiptNumber
    )
}
