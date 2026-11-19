package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import kotlinx.coroutines.flow.Flow

/** Read-only: sales are written as invoices through SalesCommands. */
@Dao
interface SaleDao {
    @Query("SELECT * FROM sale_records ORDER BY soldAt DESC")
    fun observeAllSalesChronological(): Flow<List<SaleRecordEntity>>

    @Query("SELECT * FROM sale_records")
    suspend fun getAllRawSales(): List<SaleRecordEntity>
}
