package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.ShopProfileEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import com.shopledger.pos.data.local.entity.SyncState
import kotlinx.coroutines.flow.Flow

@Dao
interface ShopPartyAndLedgerDao {

    // -------------------------------------------------------------------------
    // Shop Profile
    // -------------------------------------------------------------------------
    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    fun observeShopProfile(shopId: String = "default_shop"): Flow<ShopProfileEntity?>

    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    suspend fun getShopProfile(shopId: String = "default_shop"): ShopProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertShopProfile(profile: ShopProfileEntity)

    // -------------------------------------------------------------------------
    // Parties (Customers & Suppliers - Udhar Khata)
    // -------------------------------------------------------------------------
    @Query("SELECT * FROM parties WHERE isDeleted = 0 ORDER BY currentBalancePaise DESC, name ASC")
    fun observeAllParties(): Flow<List<PartyEntity>>

    @Query("SELECT * FROM parties WHERE isDeleted = 0")
    suspend fun getAllActivePartiesSnapshot(): List<PartyEntity>

    @Query("SELECT * FROM parties")
    suspend fun getAllRawParties(): List<PartyEntity>

    @Query("SELECT * FROM parties WHERE id = :id LIMIT 1")
    suspend fun getPartyById(id: String): PartyEntity?

    @Query("SELECT * FROM parties WHERE phone = :phone AND isDeleted = 0 LIMIT 1")
    suspend fun findPartyByPhone(phone: String): PartyEntity?

    @Query("SELECT * FROM parties WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncParties(pendingStatus: String = SyncState.PENDING_UPLOAD): List<PartyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParty(party: PartyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParties(parties: List<PartyEntity>)

    @Query("UPDATE parties SET syncStatus = :syncedStatus WHERE id IN (:ids)")
    suspend fun markPartiesSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)

    // -------------------------------------------------------------------------
    // Party Ledger Entries (Khata Debit / Jama Credit)
    // -------------------------------------------------------------------------
    @Query("SELECT * FROM party_ledger_entries WHERE partyId = :partyId ORDER BY createdAt DESC")
    fun observePartyLedger(partyId: String): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries ORDER BY createdAt DESC")
    fun observeAllLedgerEntries(): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncLedgerEntries(pendingStatus: String = SyncState.PENDING_UPLOAD): List<PartyTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntry(entry: PartyTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLedgerEntries(entries: List<PartyTransactionEntity>)

    @Query("UPDATE party_ledger_entries SET syncStatus = :syncedStatus WHERE entryId IN (:ids)")
    suspend fun markLedgerEntriesSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)

    // -------------------------------------------------------------------------
    // Invoice Headers & Lines
    // -------------------------------------------------------------------------
    @Query("SELECT * FROM invoice_headers ORDER BY issuedAt DESC")
    fun observeAllInvoiceHeaders(): Flow<List<InvoiceHeaderEntity>>

    @Query("SELECT * FROM invoice_headers WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncInvoices(pendingStatus: String = SyncState.PENDING_UPLOAD): List<InvoiceHeaderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceHeader(header: InvoiceHeaderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceHeaders(headers: List<InvoiceHeaderEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceLines(lines: List<InvoiceLineEntity>)

    @Query("UPDATE invoice_headers SET syncStatus = :syncedStatus WHERE id IN (:ids)")
    suspend fun markInvoicesSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)

    // -------------------------------------------------------------------------
    // Immutable Additive Stock Movements Log
    // -------------------------------------------------------------------------
    @Query("SELECT * FROM stock_movements WHERE skuId = :skuId ORDER BY createdAt DESC")
    fun observeMovementsForSku(skuId: String): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements WHERE movementId = :movementId LIMIT 1")
    suspend fun getMovementById(movementId: String): StockMovementEntity?

    @Query("SELECT movementId FROM stock_movements")
    suspend fun getAllMovementIds(): List<String>

    @Query("SELECT * FROM stock_movements WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncMovements(pendingStatus: String = SyncState.PENDING_UPLOAD): List<StockMovementEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStockMovement(movement: StockMovementEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStockMovements(movements: List<StockMovementEntity>): List<Long>

    @Query("UPDATE stock_movements SET syncStatus = :syncedStatus WHERE movementId IN (:ids)")
    suspend fun markMovementsSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)

    @Query(
        """
        SELECT 
            (SELECT COUNT(*) FROM parties WHERE syncStatus = :pendingStatus) +
            (SELECT COUNT(*) FROM party_ledger_entries WHERE syncStatus = :pendingStatus) +
            (SELECT COUNT(*) FROM stock_movements WHERE syncStatus = :pendingStatus)
        """
    )
    fun observePendingLedgerAndMovementCount(pendingStatus: String = SyncState.PENDING_UPLOAD): Flow<Int>
}
