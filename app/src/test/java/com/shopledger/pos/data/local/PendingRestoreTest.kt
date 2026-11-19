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

    private val now = 1_000_000_000L

    /** Stages [content] the way BackupManager + the sheet do, with a restart token made at [at]. */
    private fun stage(content: String, at: Long? = now) {
        file(".restore-staged").writeText(content)
        if (at != null) PendingRestore.armStaged(context, dbName, at)
    }

    private fun apply() = PendingRestore.applyStaged(context, dbName, now)

    @After
    fun tearDown() {
        DatabaseHealth.reset()
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
        assertFalse(apply())
        assertEquals("original", live.readText())
        assertFalse(file(".restore-pending").exists())
    }

    @Test
    fun applyStaged_movesOriginalAside_andLeavesAMarker() {
        writeOriginal()
        stage("restored")

        assertTrue(apply())

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
        stage("first")
        assertTrue(apply())
        stage("second")

        assertFalse(apply())

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
        stage("restored")
        apply()
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
        stage("restored")
        apply()
        // The original db was old, but the undo window starts when it was moved aside.
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14))
        assertTrue(file(".pre-restore").exists())
    }

    @Test
    fun stagedFileWithoutARestartToken_isDiscarded_andLiveDataIsUntouched() {
        writeOriginal()
        stage("restored", at = null)

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertFalse(file(".restore-staged").exists())
        assertFalse(file(".restore-staged.go").exists())
        assertFalse(file(".restore-pending").exists())
        assertFalse(file(".pre-restore").exists())
        assertTrue(PendingRestore.takeDiscardedNotice())
    }

    @Test
    fun stagedFileWithAnExpiredToken_isDiscarded() {
        writeOriginal()
        stage("restored", at = now - PendingRestore.TOKEN_MAX_AGE_MS - 1)

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertFalse(file(".restore-staged").exists())
        assertFalse(file(".restore-staged.go").exists())
    }

    @Test
    fun aTokenJustInsideTheWindow_isAccepted_andConsumed() {
        writeOriginal()
        stage("restored", at = now - PendingRestore.TOKEN_MAX_AGE_MS + 1)

        assertTrue(apply())

        assertEquals("restored", live.readText())
        assertFalse(file(".restore-staged.go").exists())
    }

    @Test
    fun discardStaged_removesStagedFileAndToken_butNotTheLiveData() {
        writeOriginal()
        stage("restored")

        PendingRestore.discardStaged(context, dbName)

        assertFalse(file(".restore-staged").exists())
        assertFalse(file(".restore-staged.go").exists())
        assertEquals("original", live.readText())
    }

    private fun simulateKillAfterOriginalMovedAside() {
        // The process died after: marker written, db set moved to .pre-restore, before staged -> live.
        file(".restore-pending").writeText("swapping")
        assertTrue(live.renameTo(file(".pre-restore")))
        assertTrue(file("-wal").renameTo(file(".pre-restore-wal")))
        assertTrue(file("-shm").renameTo(file(".pre-restore-shm")))
        assertFalse(live.exists())
    }

    @Test
    fun killedAfterTheLiveFileWasMovedAside_withAValidStagedBackup_neverLosesTheOriginal() {
        writeOriginal()
        stage("restored")
        simulateKillAfterOriginalMovedAside()

        assertTrue(apply())

        // The interrupted swap was undone first, then the staged backup applied; the original is the undo set again.
        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        assertEquals("original-shm", file(".pre-restore-shm").readText())
        assertTrue(PendingRestore.isPending(context, dbName))
    }

    @Test
    fun killedAfterTheLiveFileWasMovedAside_withoutAToken_putsTheOriginalBackAndAppliesNothing() {
        writeOriginal()
        stage("restored", at = null)
        simulateKillAfterOriginalMovedAside()

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        assertFalse(file(".pre-restore").exists())
        assertFalse(file(".restore-pending").exists())
        assertFalse(file(".restore-staged").exists())
    }

    @Test
    fun killedAfterStagedWasMovedIn_butBeforeTheMarkerFlipped_isCompletedAndKeepsTheUndoSet() {
        writeOriginal()
        file(".restore-pending").writeText("swapping")
        file(".pre-restore").writeText("original")
        live.writeText("restored")
        file("-wal").delete()
        file("-shm").delete()

        apply()

        assertTrue(PendingRestore.isPending(context, dbName))
        assertEquals("pending", file(".restore-pending").readText())
        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
    }

    @Test
    fun anExistingUndoSet_isNeverDeletedWhenTheLiveDbIsMissing() {
        live.parentFile?.mkdirs()
        file(".pre-restore").writeText("original")
        stage("restored")

        apply()

        // Live was missing and an undo set existed: the original went back first, then the backup was applied and the
        // original became the undo set again. At no point did it disappear.
        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
    }

    @Test
    fun rollBack_whenThePreRestoreFileIsMissing_keepsTheRestoredFileAndTheMarker_andMarksHealthFailed() {
        writeOriginal()
        stage("restored")
        apply()
        file(".pre-restore").delete()

        val ok = PendingRestore.rollBack(context, dbName)

        assertFalse(ok)
        assertEquals("restored", live.readText())
        assertTrue(file(".restore-pending").exists())
        assertTrue(DatabaseHealth.state.value is DatabaseHealth.State.Failed)
    }

    @Test
    fun rollBack_putsTheOriginalBack_andResetsHealth() {
        writeOriginal()
        stage("restored")
        apply()
        DatabaseHealth.markFailed("restored file broke", null)

        assertTrue(PendingRestore.rollBack(context, dbName))

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertFalse(file(".restore-pending").exists())
        assertEquals(DatabaseHealth.State.Ok, DatabaseHealth.state.value)
    }

    @Test
    fun killedAfterTheMainFileMovedAside_butBeforeTheWal_keepsTheOriginalWal_withoutAToken() {
        writeOriginal()
        file(".restore-pending").writeText("swapping")
        assertTrue(live.renameTo(file(".pre-restore")))

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        assertFalse(file(".pre-restore").exists())
        assertFalse(file(".restore-pending").exists())
    }

    @Test
    fun killedAfterTheMainFileMovedAside_butBeforeTheWal_keepsTheOriginalWal_withAValidStagedBackup() {
        writeOriginal()
        stage("restored")
        file(".restore-pending").writeText("swapping")
        assertTrue(live.renameTo(file(".pre-restore")))

        assertTrue(apply())

        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        assertEquals("original-shm", file(".pre-restore-shm").readText())
    }

    private fun assertNeedsSupportWithoutOpening(markerText: String?) {
        file(".pre-restore").writeText("original")
        file(".pre-restore-wal").writeText("original-wal")
        file(".restore-pending").writeText(markerText ?: "")
        assertFalse(live.exists())

        val outcome = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})

        assertTrue(outcome is PendingRestore.Outcome.NeedsSupport)
        assertFalse(live.exists())
        assertTrue(file(".restore-pending").exists())
        assertEquals("original", file(".pre-restore").readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        val health = DatabaseHealth.state.value
        assertTrue(health is DatabaseHealth.State.Failed)
        assertTrue((health as DatabaseHealth.State.Failed).safetyCopy != null)
    }

    @Test
    fun finishOrRollback_swappingMarkerWithLiveMissing_neverOpensAndNeedsSupport() = assertNeedsSupportWithoutOpening("swapping")

    @Test
    fun finishOrRollback_emptyMarkerWithLiveMissing_neverOpensAndNeedsSupport() = assertNeedsSupportWithoutOpening("")

    @Test
    fun finishOrRollback_swappingMarkerWithLivePresent_neverOpens() {
        writeOriginal()
        file(".restore-pending").writeText("swapping")

        val outcome = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})

        assertTrue(outcome is PendingRestore.Outcome.NeedsSupport)
        assertEquals("original", live.readText())
        assertTrue(file(".restore-pending").exists())
        assertTrue(DatabaseHealth.state.value is DatabaseHealth.State.Failed)
    }

    @Test
    fun moveReplacing_whenTheFinalRenameFails_keepsTheVerifiedTempCopy() {
        live.parentFile?.mkdirs()
        val source = file(".pre-restore").apply { writeText("original") }
        val target = live.apply { writeText("restored") }

        // Every rename is refused (as on a filesystem that will not rename over an existing file): the fallback
        // copies, deletes the target and then cannot rename the temp copy into place.
        val failure = runCatching { PendingRestore.moveReplacing(source, target) { _, _ -> false } }.exceptionOrNull()

        assertTrue(failure != null)
        val tmp = File(target.path + ".tmp")
        assertTrue(tmp.exists())
        assertEquals("original", tmp.readText())
        assertEquals("original", source.readText())
        assertTrue(failure!!.message!!.contains(tmp.path))
    }

    @Test
    fun moveReplacing_whenTheTargetCannotBeDeleted_removesTheTempAndKeepsTheTarget() {
        live.parentFile?.mkdirs()
        val source = file(".pre-restore").apply { writeText("original") }
        val target = live
        val tmpPath = File(target.path + ".tmp").path

        // A non-empty directory cannot be deleted as a file, so the target is never lost and the temp is cleaned up.
        target.mkdirs()
        File(target, "x").writeText("x")
        val failure = runCatching { PendingRestore.moveReplacing(source, target) { _, _ -> false } }.exceptionOrNull()

        assertTrue(failure != null)
        assertFalse(File(tmpPath).exists())
        assertTrue(File(target, "x").exists())
        assertEquals("original", source.readText())
        target.deleteRecursively()
    }

    @Test
    fun supportCopy_foldsThePreRestoreWalIntoASingleFile_andLeavesTheUndoSetUntouched() {
        live.parentFile?.mkdirs()
        val scratch = File(live.parentFile, "pending-test-scratch.db")
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(scratch, null)
        try {
            sqlite.enableWriteAheadLogging()
            sqlite.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            sqlite.execSQL("CREATE TABLE t (v TEXT)")
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            sqlite.execSQL("INSERT INTO t VALUES ('only-in-wal')")
            val wal = File(scratch.path + "-wal")
            assertTrue(wal.length() > 0)
            // Snapshot the pair while the database is still open, so the row exists only in the WAL.
            scratch.copyTo(file(".pre-restore"))
            wal.copyTo(file(".pre-restore-wal"))
        } finally {
            sqlite.close()
        }
        val preBytes = file(".pre-restore").readBytes()
        val walBytes = file(".pre-restore-wal").readBytes()

        val copy = PendingRestore.supportCopy(context, dbName)!!

        assertTrue(copy.name != file(".pre-restore").name)
        assertFalse(File(copy.path + "-wal").exists())
        val folded = android.database.sqlite.SQLiteDatabase.openDatabase(
            copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            folded.rawQuery("SELECT v FROM t", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("only-in-wal", it.getString(0))
            }
        } finally {
            folded.close()
        }
        assertTrue(preBytes.contentEquals(file(".pre-restore").readBytes()))
        assertTrue(walBytes.contentEquals(file(".pre-restore-wal").readBytes()))
        val leftovers = live.parentFile!!.listFiles { f -> f.name.startsWith("pending-test.db.pre-restore-support.tmp") }!!
        assertTrue(leftovers.joinToString { it.name }, leftovers.isEmpty())
    }

    @Test
    fun latestSafetyCopy_fallsBackToThePreRestoreFile() {
        writeOriginal()
        stage("restored")
        apply()

        assertEquals(file(".pre-restore").name, PreMigrationGuard.latestSafetyCopy(context)?.name)
    }

    /** Writes a WAL-mode database whose only row lives in the WAL into [main] and [wal] (both copies of the open pair). */
    private fun writeWalPair(main: File, wal: File) {
        live.parentFile?.mkdirs()
        val scratch = File(live.parentFile, "pending-test-scratch2.db")
        val sqlite = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(scratch, null)
        try {
            sqlite.enableWriteAheadLogging()
            sqlite.rawQuery("PRAGMA wal_autocheckpoint=0", null).use { it.moveToFirst() }
            sqlite.execSQL("CREATE TABLE t (v TEXT)")
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            sqlite.execSQL("INSERT INTO t VALUES ('only-in-wal')")
            scratch.copyTo(main)
            File(scratch.path + "-wal").copyTo(wal)
        } finally {
            sqlite.close()
        }
    }

    private fun assertFoldedHasWalRow(copy: File) {
        assertFalse(File(copy.path + "-wal").exists())
        val folded = android.database.sqlite.SQLiteDatabase.openDatabase(
            copy.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
        )
        try {
            folded.rawQuery("SELECT v FROM t", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("only-in-wal", it.getString(0))
            }
        } finally {
            folded.close()
        }
    }

    /** Kill inside rollBack after the original main moved back but before its WAL did. */
    private fun simulateKillInsideRollBackBetweenMainAndWal() {
        writeOriginal()
        file(".restore-pending").writeText("rolling-back")
        file(".pre-restore-wal").writeText("original-wal")
        file(".pre-restore-shm").writeText("original-shm")
        file("-wal").delete()
        file("-shm").delete()
        assertEquals("original", live.readText())
    }

    @Test
    fun killInsideRollBackBetweenTheMainAndWalMoves_isResumedOnNextStart_andNeverReportedAsRestored() {
        simulateKillInsideRollBackBetweenMainAndWal()

        // Nothing may open Room (and so nothing may report a restore) while the rollback is unfinished.
        val before = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})
        assertTrue(before is PendingRestore.Outcome.NeedsSupport)
        assertTrue(file(".restore-pending").exists())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        DatabaseHealth.reset()

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        assertFalse(file(".pre-restore").exists())
        assertFalse(file(".pre-restore-wal").exists())
        assertFalse(file(".pre-restore-shm").exists())
        assertFalse(file(".restore-pending").exists())
        assertTrue(PendingRestore.takeRollbackResumedNotice())
        val after = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})
        assertEquals(PendingRestore.Outcome.NotPending, after)
    }

    @Test
    fun killInsideRollBackBeforeAnyMove_replacesTheRestoredFileAndItsSidecarsWithTheOriginalSet() {
        writeOriginal()
        stage("restored")
        assertTrue(apply())
        file("-wal").writeText("restored-wal")
        file("-shm").writeText("restored-shm")
        file(".restore-pending").writeText("rolling-back")

        assertFalse(apply())

        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        assertFalse(file(".pre-restore").exists())
        assertFalse(file(".restore-pending").exists())
    }

    @Test
    fun killInsideRollBackAfterAllMovesButBeforeTheMarkerDelete_justClearsTheMarker() {
        writeOriginal()
        file(".restore-pending").writeText("rolling-back")

        val before = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})
        assertTrue(before is PendingRestore.Outcome.NeedsSupport)
        DatabaseHealth.reset()

        assertFalse(apply())

        assertFalse(file(".restore-pending").exists())
        assertEquals("original", live.readText())
        assertEquals("original-wal", file("-wal").readText())
        assertEquals("original-shm", file("-shm").readText())
        val after = PendingRestore.finishOrRollback(context, dbName, open = { error("must not open") }, close = {})
        assertTrue(after !is PendingRestore.Outcome.Restored)
        assertEquals(PendingRestore.Outcome.NotPending, after)
    }

    @Test
    fun aRollingBackMarkerThatCannotBeResumed_keepsEverythingAndTheMarker() {
        simulateKillInsideRollBackBetweenMainAndWal()
        // A non-empty directory where the WAL must go makes the move fail even through the copy fallback.
        file("-wal").mkdirs()
        File(file("-wal"), "x").writeText("x")

        assertFalse(apply())

        assertEquals("rolling-back", file(".restore-pending").readText())
        assertEquals("original", live.readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        file("-wal").deleteRecursively()
    }

    @Test
    fun emptyMarkerInApplyStaged_withNothingMoved_behavesLikeSwapping() {
        writeOriginal()
        stage("restored")
        file(".restore-pending").writeText("")

        assertTrue(apply())

        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
        assertEquals("original-wal", file(".pre-restore-wal").readText())
        assertEquals("pending", file(".restore-pending").readText())
    }

    @Test
    fun emptyMarkerInApplyStaged_withStagedAlreadyInPlace_behavesLikeSwapping() {
        writeOriginal()
        file(".restore-pending").writeText("")
        file(".pre-restore").writeText("original")
        live.writeText("restored")
        file("-wal").delete()
        file("-shm").delete()

        apply()

        assertEquals("pending", file(".restore-pending").readText())
        assertEquals("restored", live.readText())
        assertEquals("original", file(".pre-restore").readText())
    }

    @Test
    fun supportCopy_whenOnlyTheMainMovedAside_foldsTheOriginalLiveWal() {
        live.parentFile?.mkdirs()
        writeWalPair(file(".pre-restore"), file("-wal")) // main moved to .pre-restore, its WAL still live
        file(".restore-pending").writeText("swapping")
        assertFalse(live.exists())
        val preBytes = file(".pre-restore").readBytes()
        val walBytes = file("-wal").readBytes()

        val copy = PendingRestore.supportCopy(context, dbName)!!

        assertFoldedHasWalRow(copy)
        assertTrue(preBytes.contentEquals(file(".pre-restore").readBytes()))
        assertTrue(walBytes.contentEquals(file("-wal").readBytes()))
    }

    @Test
    fun supportCopy_whenRollbackMovedOnlyTheMainBack_foldsTheLiveMainWithPreRestoreWal() {
        live.parentFile?.mkdirs()
        writeWalPair(live, file(".pre-restore-wal")) // original main already live again, its WAL still aside
        file(".restore-pending").writeText("rolling-back")

        val copy = PendingRestore.supportCopy(context, dbName)!!

        assertTrue(copy.name != live.name)
        assertFoldedHasWalRow(copy)
        assertTrue(live.exists())
        assertTrue(file(".pre-restore-wal").exists())
    }

    @Test
    fun cleanup_removesOldLeftoverTemps_butNeverTheLiveFileStagedFileMarkerTokenOrEvidence() {
        writeOriginal()
        val old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val temps = listOf(".tmp", "-wal.tmp", ".restore-staged.go.tmp", ".restore-pending.tmp")
            .map { file(it).apply { writeText("x"); setLastModified(old) } }
        val keep = listOf(".restore-staged", ".restore-staged.go", ".restore-staged.tmp")
            .map { file(it).apply { writeText("x"); setLastModified(old) } }
        val evidence = File(live.parentFile, "pending-test.corrupt-1.db").apply { writeText("x"); setLastModified(old) }
        live.setLastModified(old)

        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14), dbName = dbName)

        temps.forEach { assertFalse(it.name, it.exists()) }
        keep.forEach { assertTrue(it.name, it.exists()) }
        assertTrue(live.exists())
        assertTrue(file("-wal").exists())
        assertTrue(evidence.exists())
    }

    @Test
    fun cleanup_keepsFreshLeftoverTemps_andKeepsThemWhileTheLiveDbIsMissing() {
        live.parentFile?.mkdirs()
        val old = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val tmp = file(".tmp").apply { writeText("only complete copy"); setLastModified(old) }

        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14), dbName = dbName)

        assertTrue(tmp.exists())
        writeOriginal()
        val fresh = file("-wal.tmp").apply { writeText("x") }
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, TimeUnit.DAYS.toMillis(14), dbName = dbName)
        assertTrue(fresh.exists())
    }
}
