package com.shopledger.pos.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.entity.InventoryItemEntity
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test
    fun inMemoryDatabaseOpensAndStoresAnItem() = runBlocking {
        val db = newTestDatabase()
        db.inventoryDao().insertItem(InventoryItemEntity(id = "i1", displayName = "Hinge", sortKey = "HINGE"))
        assertEquals(1, db.inventoryDao().getAllRawItems().size)
        assertEquals(1L, db.scalar("SELECT COUNT(*) FROM devices WHERE isLocal = 1"))
        db.close()
    }
}
