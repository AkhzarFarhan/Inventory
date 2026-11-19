package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shopledger.pos.data.local.entity.AppliedActionEntity
import com.shopledger.pos.data.local.entity.DeviceEntity
import com.shopledger.pos.data.local.entity.SyncOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM devices WHERE isLocal = 1 LIMIT 1")
    suspend fun getLocalDevice(): DeviceEntity?

    /** Records the last receipt sequence handed out on [dayKey]. Call inside the transaction that uses it. */
    @Query("UPDATE devices SET lastInvoiceSeq = :seq, lastSeqDate = :dayKey WHERE deviceId = :deviceId")
    suspend fun setInvoiceSeq(deviceId: String, seq: Long, dayKey: String): Int

    @Query("UPDATE devices SET deviceCode = :code WHERE deviceId = :deviceId")
    suspend fun updateDeviceCode(deviceId: String, code: String): Int

}

@Dao
interface SyncOutboxDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: SyncOutboxEntity): Long

    @Query("SELECT COUNT(*) FROM sync_outbox")
    fun observePendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sync_outbox")
    suspend fun count(): Int

    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC")
    suspend fun all(): List<SyncOutboxEntity>

    @Query("SELECT * FROM sync_outbox ORDER BY seq ASC LIMIT :limit")
    suspend fun oldest(limit: Int): List<SyncOutboxEntity>

    @Query("DELETE FROM sync_outbox WHERE seq IN (:seqs)")
    suspend fun deleteAcknowledged(seqs: List<Long>): Int
}

@Dao
interface AppliedActionDao {
    @Query("SELECT * FROM applied_actions WHERE actionId = :actionId LIMIT 1")
    suspend fun get(actionId: String): AppliedActionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(action: AppliedActionEntity)

    @Query("SELECT COUNT(*) FROM applied_actions")
    suspend fun count(): Int
}
