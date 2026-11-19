package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.shopledger.pos.data.local.entity.DEFAULT_SHOP_ID
import com.shopledger.pos.data.local.entity.InvoiceHeaderEntity
import com.shopledger.pos.data.local.entity.InvoiceLineEntity
import com.shopledger.pos.data.local.entity.PartyEntity
import com.shopledger.pos.data.local.entity.PartyTransactionEntity
import com.shopledger.pos.data.local.entity.ShopProfileEntity
import com.shopledger.pos.data.local.entity.StockMovementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShopPartyAndLedgerDao {

    // Shop profile
    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    fun observeShopProfile(shopId: String = DEFAULT_SHOP_ID): Flow<ShopProfileEntity?>

    @Query("SELECT * FROM shops WHERE shopId = :shopId LIMIT 1")
    suspend fun getShopProfile(shopId: String = DEFAULT_SHOP_ID): ShopProfileEntity?

    @Upsert
    suspend fun upsertShopProfile(profile: ShopProfileEntity)

    // Parties (customers & suppliers)
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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertParty(party: PartyEntity)

    @Update
    suspend fun updateParty(party: PartyEntity): Int

    /** Projection update; called only by LedgerWriter right after inserting the matching entry. */
    @Query("UPDATE parties SET currentBalancePaise = currentBalancePaise + :deltaPaise WHERE id = :partyId")
    suspend fun addToPartyBalance(partyId: String, deltaPaise: Long): Int

    @Query(
        "UPDATE parties SET currentBalancePaise = " +
            "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
    )
    suspend fun rebuildAllPartyBalances()

    // Party ledger (append-only)
    @Query("SELECT * FROM party_ledger_entries WHERE partyId = :partyId ORDER BY createdAt DESC")
    fun observePartyLedger(partyId: String): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries ORDER BY createdAt DESC")
    fun observeAllLedgerEntries(): Flow<List<PartyTransactionEntity>>

    @Query("SELECT * FROM party_ledger_entries")
    suspend fun getAllLedgerEntries(): List<PartyTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedgerEntry(entry: PartyTransactionEntity)

    // Invoices. INTERIM: REPLACE inserts survive only until Task 4 makes invoices immutable.
    @Query("SELECT * FROM invoice_headers ORDER BY issuedAt DESC")
    fun observeAllInvoiceHeaders(): Flow<List<InvoiceHeaderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceHeader(header: InvoiceHeaderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInvoiceLines(lines: List<InvoiceLineEntity>)

    // Stock movements (append-only)
    @Query("SELECT * FROM stock_movements WHERE skuId = :skuId ORDER BY createdAt DESC")
    fun observeMovementsForSku(skuId: String): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movements")
    suspend fun getAllMovements(): List<StockMovementEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStockMovement(movement: StockMovementEntity)
}
