package com.lpshardware.inventory.data.model

import androidx.compose.runtime.Immutable
import androidx.room.Embedded
import androidx.room.Relation
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity

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
    val quantity: Double
) {
    val lineCostPaise: Long
        get() = kotlin.math.round(unitPurchasePricePaise * quantity).toLong()

    val lineRevenuePaise: Long
        get() = kotlin.math.round(customSellingPricePaise * quantity).toLong()

    val lineProfitPaise: Long
        get() = lineRevenuePaise - lineCostPaise
}
