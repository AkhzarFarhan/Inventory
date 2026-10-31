package com.lpshardware.inventory.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lpshardware.inventory.data.local.entity.SearchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY lastSearchedAt DESC LIMIT 25")
    fun observeRecentSearches(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchFrequency DESC, lastSearchedAt DESC LIMIT 6")
    fun observeFrequentSearches(): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history WHERE normalizedQuery = :normalized LIMIT 1")
    suspend fun findByNormalizedQuery(normalized: String): SearchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSearchEntry(entry: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE id = :id")
    suspend fun deleteSearchById(id: Long)

    @Query("DELETE FROM search_history")
    suspend fun clearAllSearchHistory()
}
