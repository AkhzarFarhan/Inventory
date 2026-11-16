package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

object SyncState {
    const val SYNCED = "SYNCED"
    const val PENDING_UPLOAD = "PENDING_UPLOAD"
    /** Used for initial first-install demo items so they never overwrite a user's cloud inventory on a new device */
    const val DEMO_SEED = "DEMO_SEED"
}

typealias CatalogProductEntity = InventoryItemEntity

@Immutable
@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["sortKey"]),
        Index(value = ["shopId", "sortKey"]),
        Index(value = ["syncStatus"]),
        Index(value = ["isDeleted"])
    ]
)
data class InventoryItemEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = "default_shop",
    val displayName: String,
    val sortKey: String,
    val category: String = "Uncategorized",
    val defaultHsnCode: String = "",
    val defaultGstRateBps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    val syncStatus: String = SyncState.PENDING_UPLOAD
)
