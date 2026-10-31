package com.lpshardware.inventory.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
        Index(value = ["syncStatus"]),
        Index(value = ["isDeleted"])
    ]
)
data class ItemVariantEntity(
    @PrimaryKey
    val id: String,
    val itemId: String,
    val sizeLabel: String = "",
    val materialLabel: String = "",
    val colorFinishLabel: String = "",
    val unit: String = "pcs",
    val purchasePricePaise: Long,
    val sellingPricePaise: Long,
    val stockQuantity: Double,
    val lowStockThreshold: Double = 5.0,
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val syncStatus: String = SyncState.PENDING_UPLOAD
) {
    fun buildVariantSummary(): String {
        val parts = listOf(sizeLabel, materialLabel, colorFinishLabel).filter { it.isNotBlank() }
        return if (parts.isEmpty()) "Standard" else parts.joinToString(" · ")
    }

    val isLowStock: Boolean
        get() = !isDeleted && stockQuantity <= lowStockThreshold && stockQuantity > 0.0

    val isOutOfStock: Boolean
        get() = !isDeleted && stockQuantity <= 0.0
}
