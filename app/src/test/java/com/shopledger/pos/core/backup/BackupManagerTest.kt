package com.shopledger.pos.core.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.command.CatalogCommands
import com.shopledger.pos.data.command.VariantSpec
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import com.shopledger.pos.data.local.withShopLedgerDefaults
import com.shopledger.pos.testing.AesTestCipher
import com.shopledger.pos.testing.TestEnv
import com.shopledger.pos.testing.expectFailure
import com.shopledger.pos.testing.scalar
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

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
        BackupManager(context, AesTestCipher(), databaseProvider = ::open, dbName = dbName, clock = { clock })
    }

    private val dbDir: File get() = context.getDatabasePath(dbName).parentFile!!
    private fun dbSibling(suffix: String) = File(context.getDatabasePath(dbName).path + suffix)

    @After
    fun tearDown() {
        close()
        context.deleteDatabase(dbName)
        dbDir.listFiles { f -> f.name.startsWith(dbName) }?.forEach { it.delete() }
        File(context.filesDir, "backups").deleteRecursively()
        File(context.cacheDir, "backups").deleteRecursively()
        File(context.cacheDir, "snapshot-work").deleteRecursively()
    }

    private suspend fun addItem(actionId: String, name: String, openingMilli: Long) {
        CatalogCommands(open(), env).createItem(
            actionId, name, "Fittings",
            listOf(VariantSpec(purchasePricePaise = 4_800L, sellingPricePaise = 7_500L, openingStockMilli = openingMilli))
        )
    }

    /** What the next app start does: close everything, swap in the staged file, then confirm or roll back. */
    private fun simulateRestart(): PendingRestore.Outcome {
        close()
        PendingRestore.applyStaged(context, dbName)
        return PendingRestore.finishOrRollback(context, dbName, open = ::open, close = ::close, now = { clock })
    }

    private fun itemCount() = open().scalar("SELECT COUNT(*) FROM inventory_items")

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** A hand-built SQLite file, gzipped like a shareable backup. */
    private fun backupOf(version: Int, vararg statements: String): ByteArray {
        val file = File(context.cacheDir, "hand-built.db")
        file.delete()
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            statements.forEach { sqlite.execSQL(it) }
            sqlite.execSQL("PRAGMA user_version = $version")
        } finally {
            sqlite.close()
        }
        val bytes = file.readBytes()
        file.delete()
        return gzip(bytes)
    }

    private val inventoryTable = "CREATE TABLE inventory_items (id TEXT PRIMARY KEY NOT NULL)"
    private val masterTable = "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)"

    @Test
    fun shareableBackup_stageThenRestart_bringsBackTheExport() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val file = manager.exportShareableBackup()
        addItem("a2", "Tower Bolt", 1_000L)

        val version = file.inputStream().use { manager.stageRestoreFromShareable(it) }

        assertEquals(LpsDatabase.SCHEMA_VERSION, version)
        // Staging never touches the live database.
        assertEquals(2L, itemCount())
        assertTrue(dbSibling(".restore-staged").exists())

        val outcome = simulateRestart()

        assertEquals(PendingRestore.Outcome.Restored(emptyList()), outcome)
        assertEquals(1L, itemCount())
        assertEquals(10_000L, open().scalar("SELECT stockMilliUnits FROM item_variants"))
        assertTrue(open().scalar("SELECT COUNT(*) FROM sync_outbox") > 0)
        assertFalse(dbSibling(".restore-pending").exists())
        assertFalse(dbSibling(".restore-staged").exists())
        assertTrue(dbSibling(".pre-restore").exists())
    }

    @Test
    fun encryptedSnapshot_stageThenRestart_bringsBackTheSnapshot() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val snapshot = manager.createEncryptedSnapshot()
        addItem("a2", "Tower Bolt", 1_000L)
        assertEquals(2L, itemCount())

        val version = manager.stageRestoreFromSnapshot(snapshot)
        assertEquals(LpsDatabase.SCHEMA_VERSION, version)
        assertEquals(2L, itemCount())

        val outcome = simulateRestart()

        assertEquals(PendingRestore.Outcome.Restored(emptyList()), outcome)
        assertEquals(1L, itemCount())
        assertEquals(10_000L, open().scalar("SELECT stockMilliUnits FROM item_variants"))
        assertTrue(open().scalar("SELECT COUNT(*) FROM sync_outbox") > 0)
        assertFalse(dbSibling(".restore-pending").exists())
        assertTrue(dbSibling(".pre-restore").exists())
    }

    @Test
    fun backupRoomRefusesToOpen_isRolledBack_andOriginalDataSurvives() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        // Passes staging (integrity ok, both tables, version 3) but its Room identity hash is bogus.
        val bogus = backupOf(
            3, inventoryTable, masterTable,
            "INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'bogus-hash')"
        )
        manager.stageRestoreFromShareable(bogus.inputStream())

        val outcome = simulateRestart()

        assertTrue("expected RolledBack but was $outcome", outcome is PendingRestore.Outcome.RolledBack)
        assertEquals(1L, itemCount())
        assertEquals(10_000L, open().scalar("SELECT stockMilliUnits FROM item_variants"))
        assertFalse(dbSibling(".restore-pending").exists())
        assertFalse(dbSibling(".restore-staged").exists())
    }

    @Test
    fun rejectedBackups_leaveLiveDataUntouchedAndNoStagedFiles() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val cases = linkedMapOf(
            "not gzip" to "not a backup".toByteArray(),
            "gzip of non-SQLite bytes" to gzip(ByteArray(4_096) { 7 }),
            "SQLite without inventory_items" to backupOf(3, masterTable),
            "newer schema version" to backupOf(LpsDatabase.SCHEMA_VERSION + 1, inventoryTable, masterTable),
            "user_version 0" to backupOf(0, inventoryTable, masterTable),
            "SQLite without room_master_table" to backupOf(3, inventoryTable)
        )
        cases.forEach { (label, bytes) ->
            val failure = expectFailure<BackupException> { manager.stageRestoreFromShareable(bytes.inputStream()) }
            assertTrue("$label: message should be readable, was '${failure.message}'", !failure.message.isNullOrBlank())
            if (label == "newer schema version") assertTrue(failure.message!!.contains("newer version"))
            assertEquals("$label: live item count", 1L, itemCount())
            val leftovers = dbDir.listFiles { f -> f.name.startsWith("$dbName.restore-staged") }.orEmpty().map { it.name }
            assertEquals("$label: staged/tmp files", emptyList<String>(), leftovers)
        }
    }

    @Test
    fun snapshots_keepTheSevenNewestBySequence_evenWhenTheClockGoesBackwards() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val offsets = listOf(0L, 5_000L, 10_000L, -86_400_000L, -86_399_000L, 20_000L, -3_000_000_000L, 30_000L, 40_000L)
        val created = offsets.map { offset ->
            clock = TestEnv.BASE_TIME + offset
            manager.createEncryptedSnapshot()
        }

        val remaining = manager.listSnapshots()

        assertEquals(7, remaining.size)
        assertEquals(created.drop(2).map { it.name }, remaining.map { it.name })
        assertEquals(created.last().name, manager.latestSnapshot()!!.name)
    }

    @Test
    fun legacyTimestampSnapshots_countAsOlderThanSequenceNamedOnes() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val dir = File(context.filesDir, "backups").apply { mkdirs() }
        File(dir, "snapshot-20990101-000000-000.slsnap").writeText("legacy")
        val fresh = manager.createEncryptedSnapshot(keep = 1)
        assertEquals(listOf(fresh.name), manager.listSnapshots().map { it.name })
    }

    @Test
    fun snapshotThatFailsMidWrite_leavesNoTempFiles() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val failing = BackupManager(
            context,
            object : SnapshotCipher {
                override fun encrypt(out: OutputStream): OutputStream = object : OutputStream() {
                    private var written = 0
                    override fun write(b: Int) {
                        if (++written > 20) throw IOException("disk full")
                        out.write(b)
                    }
                }

                override fun decrypt(input: InputStream): InputStream = input
            },
            databaseProvider = ::open, dbName = dbName, clock = { clock }
        )

        expectFailure<IOException> { failing.createEncryptedSnapshot() }

        assertEquals(emptyList<String>(), File(context.cacheDir, "snapshot-work").listFiles().orEmpty().map { it.name })
        assertEquals(emptyList<String>(), File(context.filesDir, "backups").listFiles().orEmpty().map { it.name })
    }

    @Test
    fun exports_keepOnlyTheNewestFileInTheShareFolder() = runBlocking<Unit> {
        addItem("a1", "Butt Hinge", 10_000L)
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val unrelated = File(dir, "notes.txt").apply { writeText("keep me") }
        manager.exportShareableBackup()
        clock += 1_000L
        val second = manager.exportShareableBackup()

        val exports = dir.listFiles { f -> f.name.endsWith(".slbak") }.orEmpty().toList()

        assertEquals(listOf(second.name), exports.map { it.name })
        assertTrue(unrelated.exists())
    }
}
