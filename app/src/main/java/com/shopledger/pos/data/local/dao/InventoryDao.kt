package com.shopledger.pos.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.model.InventoryItemWithVariants
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

    @Query("SELECT COUNT(*) FROM inventory_items")
    suspend fun countItems(): Int

    @Query("SELECT * FROM inventory_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): InventoryItemEntity?

    @Query("SELECT * FROM item_variants WHERE id = :variantId LIMIT 1")
    suspend fun getVariantById(variantId: String): ItemVariantEntity?

    @Query("SELECT * FROM item_variants WHERE itemId = :itemId")
    suspend fun getVariantsForItem(itemId: String): List<ItemVariantEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItem(item: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVariant(variant: ItemVariantEntity)

    @Update
    suspend fun updateItem(item: InventoryItemEntity): Int

    @Update
    suspend fun updateVariant(variant: ItemVariantEntity): Int

    /** Projection update; called only by LedgerWriter right after inserting the matching movement. */
    @Query("UPDATE item_variants SET stockMilliUnits = stockMilliUnits + :deltaMilli WHERE id = :variantId")
    suspend fun addToStockProjection(variantId: String, deltaMilli: Long): Int

    @Query(
        "UPDATE item_variants SET stockMilliUnits = " +
            "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
    )
    suspend fun rebuildAllStockProjections()

    @Query("UPDATE inventory_items SET isDeleted = 1, updatedAt = :now WHERE id = :itemId")
    suspend fun softDeleteItem(itemId: String, now: Long)

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now WHERE itemId = :itemId")
    suspend fun softDeleteVariantsForItem(itemId: String, now: Long)

    @Query("UPDATE item_variants SET isDeleted = 1, updatedAt = :now WHERE id = :variantId")
    suspend fun softDeleteSingleVariant(variantId: String, now: Long)
}
