package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val DEFAULT_SHOP_ID = "default_shop"

typealias CatalogProductEntity = InventoryItemEntity

@Immutable
@Entity(
    tableName = "inventory_items",
    indices = [
        Index(value = ["sortKey"]),
        Index(value = ["shopId", "sortKey"]),
        Index(value = ["isDeleted"])
    ]
)
data class InventoryItemEntity(
    @PrimaryKey
    val id: String,
    val shopId: String = DEFAULT_SHOP_ID,
    val displayName: String,
    val sortKey: String,
    val category: String = "Uncategorized",
    val defaultHsnCode: String = "",
    val defaultGstRateBps: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** Last catalog edit. Stock changes never touch this (stock lives in stock_movements). */
    val updatedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false,
    /** Starter-catalog rows: never queued for cloud sync. */
    val isDemo: Boolean = false,
    val createdByUid: String = "",
    val deviceId: String = ""
)
