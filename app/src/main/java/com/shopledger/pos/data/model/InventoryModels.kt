package com.shopledger.pos.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Relation
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity

@Immutable
data class InventoryItemWithVariants(
    @Embedded
    val item: InventoryItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "itemId"
    )
    val allVariants: List<ItemVariantEntity>
) {
    val variants: List<ItemVariantEntity>
        get() = allVariants.filter { !it.isDeleted }.sortedWith(
            compareBy<ItemVariantEntity> { it.sizeLabel }
                .thenBy { it.materialLabel }
                .thenBy { it.colorFinishLabel }
        )

    val totalStockAcrossVariants: Double
        get() = variants.sumOf { it.stockQuantity }

    val hasLowStockVariant: Boolean
        get() = variants.any { it.isLowStock || it.isOutOfStock }

    val firstLetterHeader: Char
        get() {
            val ch = item.sortKey.firstOrNull()?.uppercaseChar() ?: '#'
            return if (ch in 'A'..'Z') ch else '#'
        }
}

@Immutable
data class CartLineItem(
    val itemId: String,
    val variantId: String,
    val itemName: String,
    val variantSummary: String,
    val unit: String,
    val availableStock: Double,
    val unitPurchasePricePaise: Long,
    val defaultSellingPricePaise: Long,
    val customSellingPricePaise: Long,
    val quantity: Double,
    val skuCode: String = "",
    val barcode: String = "",
    val gstRateBps: Int = 0
) {
    val lineCostPaise: Long
        get() = kotlin.math.round(unitPurchasePricePaise * quantity).toLong()

    val lineRevenuePaise: Long
        get() = kotlin.math.round(customSellingPricePaise * quantity).toLong()

    val lineProfitPaise: Long
        get() = lineRevenuePaise - lineCostPaise
}
