package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToLong

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
        Index(value = ["syncStatus"]),
        Index(value = ["isDeleted"])
    ]
)
data class ItemVariantEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
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
    val stockQuantity: Double,
    val lowStockThreshold: Double = 5.0,
    val stockMilliUnits: Long = (stockQuantity * 1000.0).roundToLong(),
    val lowStockMilliUnits: Long = (lowStockThreshold * 1000.0).roundToLong(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val syncStatus: String = SyncState.PENDING_UPLOAD
) {
    val attr1Value: String get() = sizeLabel
    val attr2Value: String get() = materialLabel
    val attr3Value: String get() = colorFinishLabel

    fun buildVariantSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
    }

    val isLowStock: Boolean
        get() = !isDeleted && stockQuantity <= lowStockThreshold && stockQuantity > 0.0

    val isOutOfStock: Boolean
        get() = !isDeleted && stockQuantity <= 0.0
}
