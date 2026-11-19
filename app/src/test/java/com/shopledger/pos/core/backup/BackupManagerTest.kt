package com.shopledger.pos.core.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.VariantSpec
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.withShopLedgerDefaults
import com.shopledger.pos.testing.AesTestCipher
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "backup-test.db"
    private val env = TestEnv()
    private var db: LpsDatabase? = null
    private var clock = TestEnv.BASE_TIME

    private fun open(): LpsDatabase = db ?: Room.databaseBuilder(context, LpsDatabase::class.java, dbName)
        .withShopLedgerDefaults()
        .allowMainThreadQueries()
        .build()
        .also { db = it }

    private fun close() {
        db?.close()
        db = null
    }

    private val manager by lazy {
        BackupManager(context, AesTestCipher(), databaseProvider = ::open, closeDatabase = ::close, dbName = dbName, clock = { clock })
    }

    @After
    fun tearDown() {
        close()
        context.deleteDatabase(dbName)
        File(context.filesDir, "backups").deleteRecursively()
        File(context.cacheDir, "backups").deleteRecursively()
    }

    private suspend fun addItem(actionId: String, name: String, openingMilli: Long) {
        CatalogCommands(open(), env).createItem(
            actionId, name, "Fittings",
            listOf(VariantSpec(purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli))
        )
    }

    @Test
    fun encryptedSnapshot_thenChange_thenRestore_bringsBackTheSnapshot() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val snapshot = manager.createEncryptedSnapshot()
        addItem("a2", "Tower Bolt", 1_000L)
        assertEquals(2L, open().scalar("SELECT COUNT(*) FROM inventory_items"))

        val report = manager.restoreFromSnapshot(snapshot)

        assertEquals(3, report.fromSchemaVersion)
        assertEquals(emptyList<String>(), report.violations)
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
        assertEquals(10_000L, open().scalar("SELECT stockMilliUnits FROM item_variants"))
        assertTrue(open().scalar("SELECT COUNT(*) FROM sync_outbox") > 0)
    }

    @Test
    fun snapshots_keepOnlyTheNewestSeven() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        repeat(9) {
            clock += 1_000L
            manager.createEncryptedSnapshot()
        }
        assertEquals(7, manager.listSnapshots().size)
    }

    @Test
    fun shareableBackup_roundTrips() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val file = manager.exportShareableBackup()
        addItem("a2", "Tower Bolt", 1_000L)
        file.inputStream().use { manager.restoreFromShareable(it) }
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
    }

    @Test
    fun garbageFile_isRejectedAndLiveDataIsUntouched() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        expectFailure<BackupException> { manager.restoreFromShareable("not a backup".byteInputStream()) }
        assertEquals(1L, open().scalar("SELECT COUNT(*) FROM inventory_items"))
    }
}
