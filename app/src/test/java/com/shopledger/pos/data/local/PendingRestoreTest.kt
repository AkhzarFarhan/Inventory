package com.shopledger.pos.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** File-level behaviour of the restore swap, with plain files standing in for databases. */
@RunWith(AndroidJUnit4::class)
class PendingRestoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "pending-test.db"
    private val live: File get() = context.getDatabasePath(dbName)

    private fun file(suffix: String) = File(live.path + suffix)

    @After
    fun tearDown() {
        live.parentFile?.listFiles { f -> f.name.startsWith("pending-test") }?.forEach { it.delete() }
    }

    private fun writeOriginal() {
        live.parentFile?.mkdirs()
        live.writeText("original")
        file("-wal").writeText("original-wal")
        file("-shm").writeText("original-shm")
    }

    @Test
    fun noStagedFile_isANoOp() {
        writeOriginal()
        assertFalse(PendingRestore.applyStaged(context, dbName))
        assertEquals("original", live.readText())
        assertFalse(file(".restore-pending").exists())
    }

    @Test
    fun applyStaged_movesOriginalAside_andLeavesAMarker() {
        writeOriginal()
        file(".restore-staged").writeText("restored")

        assertTrue(PendingRestore.applyStaged(context, dbName))

        assertEquals("restored", live.readText())
        assertFalse(file("-wal").exists())
        assertFalse(file("-shm").exists())
        assertFalse(file(".restore-staged").exists())
        assertTrue(file(".restore-pending").exists())
        assertEquals("original", file(".pre-restore").readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        assertEquals("original-shm", file(".pre-restore-shm").readText())
        assertTrue(PendingRestore.isPending(context, dbName))
    }

    @Test
    fun anUnconfirmedRestore_isNeverOverwrittenByAnotherStagedFile() {
        writeOriginal()
        file(".restore-staged").writeText("first")
        assertTrue(PendingRestore.applyStaged(context, dbName))
        file(".restore-staged").writeText("second")

        assertFalse(PendingRestore.applyStaged(context, dbName))

        assertEquals("first", live.readText())
        assertEquals("original", file(".pre-restore").readText())
        assertTrue(file(".restore-staged").exists())
    }

    @Test
    fun finishOrRollback_withoutMarker_isNotPending() {
        writeOriginal()
        val outcome = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})
        assertEquals(PendingRestore.Outcome.NotPending, outcome)
        assertEquals("original", live.readText())
    }

    @Test
    fun whenOpeningFails_originalFilesComeBack_andTheMarkerIsRemoved() {
        writeOriginal()
        file(".restore-staged").writeText("restored")
        PendingRestore.applyStaged(context, dbName)
        file("-wal").writeText("restored-wal") // what Room would have left behind
        var closed = false

        val outcome = PendingRestore.finishOrRollback(
            context, dbName,
            open = { throw IllegalStateException("cannot open") },
            close = { closed = true }
        )

        assertTrue(outcome is PendingRestore.Outcome.RolledBack)
        assertEquals("cannot open", (outcome as PendingRestore.Outcome.RolledBack).reason)
        assertTrue(closed)
        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        assertFalse(file(".pre-restore").exists())
        assertFalse(file(".restore-pending").exists())
    }

    @Test
    fun cleanup_removesOldUndoSets_butNeverTheLiveFileOrCorruptEvidence() {
        writeOriginal()
        val old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val undo = listOf(".pre-restore", ".pre-restore-wal", ".pre-restore-shm")
            .map { file(it).apply { writeText("x"); setLastModified(old) } }
        val evidence = File(live.parentFile, "pending-test.corrupt-1.db").apply { writeText("x"); setLastModified(old) }
        live.setLastModified(old)

        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14))

        undo.forEach { assertFalse(it.name, it.exists()) }
        assertTrue(live.exists())
        assertTrue(evidence.exists())
    }

    @Test
    fun cleanup_keepsAFreshUndoSet() {
        writeOriginal()
        live.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30))
        file(".restore-staged").writeText("restored")
        PendingRestore.applyStaged(context, dbName)
        // The original db was old, but the undo window starts when it was moved aside.
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14))
        assertTrue(file(".pre-restore").exists())
    }
}
