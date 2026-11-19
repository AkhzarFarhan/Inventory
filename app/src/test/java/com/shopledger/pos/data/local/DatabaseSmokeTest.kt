package com.shopledger.pos.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test
    fun inMemoryDatabaseOpensAndStoresAnItem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LpsDatabase::class.java
        ).allowMainThreadQueries().build()
        db.inventoryDao().upsertItem(InventoryItemEntity(id = "i1", displayName = "Hinge", sortKey = "HINGE"))
        assertEquals(1, db.inventoryDao().getAllRawItems().size)
        db.close()
    }
}
