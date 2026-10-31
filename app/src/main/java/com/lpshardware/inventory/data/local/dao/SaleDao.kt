package com.lpshardware.inventory.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface SaleDao {

    @Query("SELECT * FROM sale_records ORDER BY soldAt DESC")
    fun observeAllSalesChronological(): Flow<List<SaleRecordEntity>>

    @Query("SELECT * FROM sale_records")
    suspend fun getAllRawSales(): List<SaleRecordEntity>

    @Query("SELECT DISTINCT receiptNumber FROM sale_records WHERE yearInt = :year AND monthInt = :month AND dayInt = :day")
    suspend fun getDistinctReceiptNumbersForDay(year: Int, month: Int, day: Int): List<String>

    @Query("SELECT * FROM sale_records WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncSales(pendingStatus: String = SyncState.PENDING_UPLOAD): List<SaleRecordEntity>

    @Query("SELECT COUNT(*) FROM sale_records WHERE syncStatus = :pendingStatus")
    fun observePendingSaleCount(pendingStatus: String = SyncState.PENDING_UPLOAD): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSale(sale: SaleRecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSales(sales: List<SaleRecordEntity>)

    @Query("UPDATE sale_records SET isReturned = 1, updatedAt = :now, syncStatus = :syncStatus WHERE id = :saleId")
    suspend fun markSaleReturned(
        saleId: String,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query("UPDATE sale_records SET syncStatus = :syncedStatus WHERE id IN (:ids)")
    suspend fun markSalesSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)
}
