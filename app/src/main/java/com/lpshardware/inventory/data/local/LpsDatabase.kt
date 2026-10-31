package com.lpshardware.inventory.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lpshardware.inventory.data.local.dao.InventoryDao
import com.lpshardware.inventory.data.local.dao.SaleDao
import com.lpshardware.inventory.data.local.dao.SearchHistoryDao
import com.lpshardware.inventory.data.local.entity.InventoryItemEntity
import com.lpshardware.inventory.data.local.entity.ItemVariantEntity
import com.lpshardware.inventory.data.local.entity.SaleRecordEntity
import com.lpshardware.inventory.data.local.entity.SearchHistoryEntity

@Database(
    entities = [
        InventoryItemEntity::class,
        ItemVariantEntity::class,
        SaleRecordEntity::class,
        SearchHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LpsDatabase : RoomDatabase() {

    abstract fun inventoryDao(): InventoryDao
    abstract fun saleDao(): SaleDao
    abstract fun searchHistoryDao(): SearchHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: LpsDatabase? = null

        fun getInstance(context: Context): LpsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LpsDatabase::class.java,
                    "lps_hardware_inventory.db"
                )
                    // WRITE_AHEAD_LOGGING allows concurrent UI reads while background sync writes
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
