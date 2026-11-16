package com.shopledger.pos.data.local.entity

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Immutable
@Entity(
    tableName = "search_history",
    indices = [
        Index(value = ["shopId", "normalizedQuery"], unique = true),
        Index(value = ["lastSearchedAt"])
    ]
)
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val shopId: String = "default_shop",
    val rawQuery: String,
    val normalizedQuery: String,
    val resultCount: Int = 0,
    val searchFrequency: Int = 1,
    val lastSearchedAt: Long = System.currentTimeMillis()
)
