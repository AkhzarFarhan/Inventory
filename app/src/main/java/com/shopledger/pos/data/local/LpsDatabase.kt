package com.shopledger.pos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.shopledger.pos.data.local.dao.InventoryDao
import com.shopledger.pos.data.local.dao.SaleDao
import com.shopledger.pos.data.local.dao.SearchHistoryDao
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.data.local.entity.ItemVariantEntity
import com.shopledger.pos.data.local.entity.SaleRecordEntity
import com.shopledger.pos.data.local.entity.SearchHistoryEntity

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
