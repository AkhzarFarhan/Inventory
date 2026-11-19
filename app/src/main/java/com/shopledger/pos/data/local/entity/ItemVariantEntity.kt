package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

typealias ProductSkuEntity = ItemVariantEntity

@Immutable
@Entity(
    tableName = "item_variants",
    foreignKeys = [
        ForeignKey(
            entity = InventoryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["itemId"]),
        Index(value = ["shopId", "barcode"]),
        Index(value = ["isDeleted"])
    ]
)
data class ItemVariantEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val itemId: String,
    val skuCode: String = "",
    val barcode: String = "",
    val sizeLabel: String = "",
    val materialLabel: String = "",
    val colorFinishLabel: String = "",
    val unit: String = "pcs",
    val mrpPaise: Long = 0L,
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val gstRateBps: Int = 0,
    /** Cached projection: always equals SUM(stock_movements.deltaMilliUnits) for this SKU (invariant I1). */
    val stockMilliUnits: Long = 0L,
    val lowStockMilliUnits: Long = 5_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val isDemo: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
) {
    val stockQuantity: Double get() = stockMilliUnits / 1000.0
    val lowStockThreshold: Double get() = lowStockMilliUnits / 1000.0

    val attr1Value: String get() = sizeLabel
    val attr2Value: String get() = materialLabel
    val attr3Value: String get() = colorFinishLabel

    fun buildVariantSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
    }

    val isLowStock: Boolean
        get() = !isDeleted && stockMilliUnits > 0L && stockMilliUnits <= lowStockMilliUnits

    val isOutOfStock: Boolean
        get() = !isDeleted && stockMilliUnits <= 0L

    /** Several phones selling offline can push stock below zero; we show it instead of hiding it. */
    val isOversold: Boolean
        get() = !isDeleted && stockMilliUnits < 0L
}
