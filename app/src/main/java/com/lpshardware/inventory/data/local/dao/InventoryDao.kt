package com.lpshardware.inventory.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SyncState
import com.lpshardware.inventory.data.model.InventoryItemWithVariants
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {

    @Transaction
    @Query("SELECT * FROM inventory_items WHERE isDeleted = 0 ORDER BY sortKey ASC")
    fun observeAllActiveItemsAlphabetically(): Flow<List<InventoryItemWithVariants>>

    @Transaction
    @Query("SELECT * FROM inventory_items WHERE isDeleted = 0 ORDER BY sortKey ASC")
    suspend fun getAllActiveItemsSnapshot(): List<InventoryItemWithVariants>

    @Query("SELECT * FROM inventory_items")
    suspend fun getAllRawItems(): List<InventoryItemEntity>

    @Query("SELECT * FROM item_variants")
    suspend fun getAllRawVariants(): List<ItemVariantEntity>

    @Query("SELECT * FROM inventory_items WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncItems(pendingStatus: String = SyncState.PENDING_UPLOAD): List<InventoryItemEntity>

    @Query("SELECT * FROM item_variants WHERE syncStatus = :pendingStatus")
    suspend fun getPendingSyncVariants(pendingStatus: String = SyncState.PENDING_UPLOAD): List<ItemVariantEntity>

    @Query("SELECT COUNT(*) FROM inventory_items WHERE syncStatus = :pendingStatus")
    fun observePendingItemCount(pendingStatus: String = SyncState.PENDING_UPLOAD): Flow<Int>

    @Query("SELECT COUNT(*) FROM item_variants WHERE syncStatus = :pendingStatus")
    fun observePendingVariantCount(pendingStatus: String = SyncState.PENDING_UPLOAD): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(item: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItems(items: List<InventoryItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariant(variant: ItemVariantEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVariants(variants: List<ItemVariantEntity>)

    @Update
    suspend fun updateVariant(variant: ItemVariantEntity)

    @Query("SELECT * FROM item_variants WHERE id = :variantId LIMIT 1")
    suspend fun getVariantById(variantId: String): ItemVariantEntity?

    @Query("DELETE FROM item_variants WHERE syncStatus = :demoSeedStatus")
    suspend fun deleteDemoSeedVariants(demoSeedStatus: String = SyncState.DEMO_SEED)

    @Query("DELETE FROM inventory_items WHERE syncStatus = :demoSeedStatus")
    suspend fun deleteDemoSeedItems(demoSeedStatus: String = SyncState.DEMO_SEED)

    @Query(
        """
        UPDATE item_variants 
        SET stockQuantity = CASE WHEN (stockQuantity - :qtySold) < 0 THEN 0 ELSE (stockQuantity - :qtySold) END,
            updatedAt = :now,
            syncStatus = :syncStatus
        WHERE id = :variantId
        """
    )
    suspend fun deductVariantStock(
        variantId: String,
        qtySold: Double,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query(
        """
        UPDATE item_variants 
        SET stockQuantity = stockQuantity + :qtyRestored,
            updatedAt = :now,
            syncStatus = :syncStatus
        WHERE id = :variantId
        """
    )
    suspend fun restoreVariantStock(
        variantId: String,
        qtyRestored: Double,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query("UPDATE inventory_items SET isDeleted = 1, updatedAt = :now, syncStatus = :syncStatus WHERE id = :itemId")
    suspend fun softDeleteItem(
        itemId: String,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now, syncStatus = :syncStatus WHERE itemId = :itemId")
    suspend fun softDeleteVariantsForItem(
        itemId: String,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now, syncStatus = :syncStatus WHERE id = :variantId")
    suspend fun softDeleteSingleVariant(
        variantId: String,
        now: Long = System.currentTimeMillis(),
        syncStatus: String = SyncState.PENDING_UPLOAD
    )

    @Query("UPDATE inventory_items SET syncStatus = :syncedStatus WHERE id IN (:ids)")
    suspend fun markItemsSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)

    @Query("UPDATE item_variants SET syncStatus = :syncedStatus WHERE id IN (:ids)")
    suspend fun markVariantsSynced(ids: List<String>, syncedStatus: String = SyncState.SYNCED)
}
