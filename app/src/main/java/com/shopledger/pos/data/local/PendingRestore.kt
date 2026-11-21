package com.shopledger.pos.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Second half of a restore. BackupManager only ever *stages* a validated backup as `<db>.restore-staged`; this swaps it
 * in at the start of a fresh process (before anything has the database open) and, once Room has opened and migrated it,
 * rebuilds everything derived. If that fails the original files are put back.
 *
 * The replaced database is kept as `<db>.pre-restore` (+ `-wal`/`-shm`) as a one-shot undo until the 14-day cleanup.
 */
object PendingRestore {
    private const val STAGED = ".restore-staged"
    private const val TOKEN = ".restore-staged.go"
    private const val PRE_RESTORE = ".pre-restore"
    private const val MARKER = ".restore-pending"
    private const val STATE_SWAPPING = "swapping"
    private const val STATE_PENDING = "pending"
    private const val STATE_ROLLING_BACK = "rolling-back"
    private const val STATE_UNDOING_SWAP = "undoing-swap"
    private val SUFFIXES = listOf("", "-wal", "-shm")

    /** A staged file is only applied if the user's restart happened within this long of staging. */
    const val TOKEN_MAX_AGE_MS = 10 * 60 * 1000L
    private const val CLOCK_SKEW_MS = 60 * 1000L

    private val discardedNotice = AtomicBoolean(false)
    private val rollbackResumedNotice = AtomicBoolean(false)

    sealed interface Outcome {
        data object NotPending : Outcome
        data class Restored(val violations: List<String>) : Outcome

        /** [originalBack] is false only when putting the previous files back failed; then health is Failed. */
        data class RolledBack(val reason: String, val originalBack: Boolean = true) : Outcome

        /**
         * The restore state is ambiguous (live database missing, or the swap marker still says `swapping`). Room was NOT
         * opened, nothing was deleted or moved, the marker stays and [DatabaseHealth] is Failed with the best copy for support.
         */
        data class NeedsSupport(val reason: String) : Outcome
    }

    /** True while a swapped-in backup has not yet been confirmed by [finishOrRollback]. */
    fun isPending(context: Context, dbName: String): Boolean = File(context.getDatabasePath(dbName).path + MARKER).exists()

    /**
     * Written just before the app restarts for a restore: proves the user's restart is what applies the staged file.
     * Without a fresh token [applyStaged] throws the staged file away instead of applying it.
     */
    fun armStaged(context: Context, dbName: String, now: Long = System.currentTimeMillis()) {
        val live = context.getDatabasePath(dbName)
        writeDurably(File(live.path + TOKEN), now.toString())
    }

    /** Undoes staging (staged file and token). Never touches the live data. */
    fun discardStaged(context: Context, dbName: String) {
        val live = context.getDatabasePath(dbName)
        deleteIfExists(File(live.path + STAGED))
        deleteIfExists(File(live.path + STAGED + ".tmp"))
        deleteIfExists(File(live.path + TOKEN))
    }

    /** True once if an unfinished (token-less or expired) restore was discarded; the app reports it to the user. */
    fun takeDiscardedNotice(): Boolean = discardedNotice.getAndSet(false)

    /** True once if [applyStaged] finished an interrupted rollback (the original data is back; this is never a restore). */
    fun takeRollbackResumedNotice(): Boolean = rollbackResumedNotice.getAndSet(false)

    /**
     * Swaps a staged backup in. Returns true if it did. On any failure the original files are put back, the staged file
     * is left for a later attempt and false is returned; the live data is never lost.
     *
     * Order matters for a process kill at any point: the intent marker is written before any file moves, the previous
     * `.pre-restore` set is only deleted while the live database is present and no marker exists, and a half-done swap
     * found at the start is finished or undone before anything else.
     */
    fun applyStaged(context: Context, dbName: String, now: Long = System.currentTimeMillis()): Boolean =
        applyStaged(context, dbName, now) { it.readText() }

    /** [readMarker] and [delete] are injectable so tests can simulate a transient I/O failure reading the marker / deleting the old undo set. */
    internal fun applyStaged(
        context: Context,
        dbName: String,
        now: Long,
        delete: (File) -> Boolean = { it.delete() },
        readMarker: (File) -> String
    ): Boolean {
        val live = context.getDatabasePath(dbName)
        val staged = File(live.path + STAGED)
        val token = File(live.path + TOKEN)
        val marker = File(live.path + MARKER)
        val preMain = File(live.path + PRE_RESTORE)

        // A successfully read empty/unknown marker is `swapping`, like finishOrRollback. A marker that cannot be READ
        // (transient I/O) is never interpreted or deleted: everything stays and nothing happens this launch.
        val state = if (marker.exists()) {
            val text = try {
                readMarker(marker)
            } catch (t: Throwable) {
                return false
            }
            stateOf(text)
        } else {
            null
        }

        // 0. An interrupted rollback is resumed first: the original's files go back over the live names, then the
        // marker goes. Nothing else happens in this launch, and it is never reported as a restore.
        if (state == STATE_ROLLING_BACK) {
            return try {
                completeRollback(live)
                marker.delete()
                rollbackResumedNotice.set(true)
                false
            } catch (t: Throwable) {
                false // everything stays exactly where it is, marker included
            }
        }

        // 0b. An interrupted undo of a forward swap is resumed (and the restore abandoned). Only the explicit
        // `undoing-swap` marker proves a `.pre-restore-wal` is the original's: a `swapping` marker with the live main and
        // no `.pre-restore` main is a kill while deleting an OLD undo set, whose WAL is stale. The staged file is discarded
        // BEFORE the marker goes, so a kill in between cannot re-apply a cancelled restore.
        if (state == STATE_UNDOING_SWAP) {
            return try {
                restoreFromPre(live)
                discardStaged(context, dbName)
                marker.delete()
                rollbackResumedNotice.set(true)
                false
            } catch (t: Throwable) {
                false // everything stays exactly where it is, marker included
            }
        }

        // 1. Finish or undo an earlier interrupted swap before anything else.
        if (!live.exists() && preMain.exists()) {
            // The original was moved aside and the swap never completed: put it back. The undo moves main then WAL, so
            // it is logged durably first; a kill in between leaves `undoing-swap`, which the next start resumes.
            try {
                writeDurably(marker, STATE_UNDOING_SWAP)
                restoreFromPre(live)
                marker.delete()
            } catch (t: Throwable) {
                return false // both sets stay exactly where they are
            }
        } else if (state != null) {
            if (state != STATE_SWAPPING) return false // unconfirmed restore: .pre-restore is the only original copy
            if (live.exists() && !staged.exists() && preMain.exists()) {
                // The staged file did get into place; only the marker update was missed.
                return runCatching { writeDurably(marker, STATE_PENDING); token.delete() }.isSuccess
            }
            // Otherwise nothing was moved (the live file is still the original): drop the stale intent and go on.
            marker.delete()
        }

        if (!staged.exists()) {
            deleteIfExists(token)
            return false
        }

        // 2. Apply only on the user's own restart.
        if (!tokenIsFresh(token, now)) {
            discardStaged(context, dbName)
            discardedNotice.set(true)
            return false
        }

        // The live file is present (or there is nothing to protect), so an older undo set may go. It goes BEFORE the
        // marker is written (a kill while deleting must not look like an interrupted swap), sidecars before the main
        // file, and every delete is checked: on failure nothing is marked or moved and the staged file is kept.
        if (live.exists()) {
            try {
                SUFFIXES.asReversed().forEach { suffix ->
                    val old = File(live.path + PRE_RESTORE + suffix)
                    if (old.exists() && !delete(old)) throw IllegalStateException("Could not delete ${old.name}")
                }
            } catch (t: Throwable) {
                return false
            }
        }

        val moved = mutableListOf<Pair<File, File>>() // from live name -> pre-restore name
        var stagedInPlace = false
        try {
            // 3. Intent log first, then move files.
            writeDurably(marker, STATE_SWAPPING)
            for (suffix in SUFFIXES) {
                val from = File(live.path + suffix)
                if (!from.exists()) continue
                val to = File(live.path + PRE_RESTORE + suffix)
                if (!from.renameTo(to)) throw IllegalStateException("Could not move ${from.name} aside")
                moved += from to to
            }
            if (!staged.renameTo(live)) throw IllegalStateException("Could not move the staged backup into place")
            stagedInPlace = true
            writeDurably(marker, STATE_PENDING)
            token.delete()
            // renameTo keeps the old modification time; restart the 14-day undo window from now.
            val stamp = System.currentTimeMillis()
            moved.forEach { (_, to) -> to.setLastModified(stamp) }
            return true
        } catch (t: Throwable) {
            val undone = runCatching {
                if (stagedInPlace) check(live.renameTo(staged)) { "Could not move the staged backup back" }
                moved.asReversed().forEach { (from, to) -> moveReplacing(to, from) }
            }
            // If undoing failed the marker stays so the next start finishes the job.
            if (undone.isSuccess) marker.delete()
            return false
        }
    }

    private fun tokenIsFresh(token: File, now: Long): Boolean {
        val stamp = runCatching { token.readText().trim().toLong() }.getOrNull() ?: return false
        val age = now - stamp
        return age in -CLOCK_SKEW_MS..TOKEN_MAX_AGE_MS
    }

    /**
     * If a restore is pending: open the database (running any migration), rebuild stock/party projections and the
     * outbox in one transaction and check the ledger invariants. If anything throws, or [DatabaseHealth] was marked
     * Failed while opening the restored file, [close] the database, put the original files back and report
     * [Outcome.RolledBack] (health is reset so the original can be opened normally).
     */
    fun finishOrRollback(
        context: Context,
        dbName: String,
        open: () -> LpsDatabase,
        close: () -> Unit,
        now: () -> Long = { System.currentTimeMillis() }
    ): Outcome {
        val live = context.getDatabasePath(dbName)
        val marker = File(live.path + MARKER)
        if (!marker.exists()) return Outcome.NotPending
        // Never open Room over a missing original or an unfinished swap: Room would create an empty database next to
        // the only copy of the books. Any marker state other than `pending` (swapping, rolling-back, empty or
        // unreadable) means files may be half-moved.
        if (!live.exists() || markerState(marker) != STATE_PENDING) {
            val reason = if (!live.exists()) {
                "The data file is missing while a restore is unfinished. Nothing was deleted."
            } else {
                "A restore was interrupted part-way. Nothing was deleted."
            }
            DatabaseHealth.markFailed(reason, supportCopy(context, dbName))
            return Outcome.NeedsSupport(reason)
        }
        return try {
            val database = open()
            failIfUnhealthy()
            val sqlite = database.openHelper.writableDatabase
            failIfUnhealthy()
            val hasPurchases = hasPurchaseTables(sqlite)
            sqlite.beginTransaction()
            try {
                sqlite.execSQL(
                    "UPDATE item_variants SET stockMilliUnits = " +
                        "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
                )
                sqlite.execSQL(
                    "UPDATE parties SET currentBalancePaise = " +
                        "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
                )
                sqlite.execSQL("DELETE FROM sync_outbox")
                OutboxRebuilder.enqueueEverything(sqlite, now())
                if (hasPurchases) OutboxRebuilder.enqueuePurchases(sqlite, now())
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            val violations = IntegrityChecker.findViolations(sqlite, includePurchases = hasPurchases)
            failIfUnhealthy()
            marker.delete()
            Outcome.Restored(violations)
        } catch (t: Throwable) {
            val reason = t.message ?: t.javaClass.simpleName
            runCatching(close)
            val undone = rollBack(context, dbName)
            Outcome.RolledBack(
                if (undone) reason else "$reason (restoring the previous data failed; both copies were kept)",
                originalBack = undone
            )
        }
    }

    private fun hasPurchaseTables(db: SupportSQLiteDatabase): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name='purchase_bills'").use { it.moveToFirst() }

    private fun failIfUnhealthy() {
        (DatabaseHealth.state.value as? DatabaseHealth.State.Failed)?.let { throw IllegalStateException(it.reason) }
    }

    /**
     * Puts the pre-restore files back as the live database. The caller must have closed the database first.
     * The restored file is only touched once the `.pre-restore` file is confirmed present; if the move back fails the
     * restored file, the `.pre-restore` set and the marker all stay, [DatabaseHealth] is marked Failed with the
     * `.pre-restore` file as the safety copy, and false is returned. On success the marker is removed, health is reset
     * so the original can be opened, and true is returned.
     */
    fun rollBack(context: Context, dbName: String): Boolean {
        val live = context.getDatabasePath(dbName)
        val marker = File(live.path + MARKER)
        val preMain = File(live.path + PRE_RESTORE)
        if (!preMain.exists()) {
            DatabaseHealth.reset()
            DatabaseHealth.markFailed(
                "The restored data could not be opened and the previous data file was not found. Nothing was deleted.", null
            )
            return false
        }
        return try {
            // Durable intent first: from here on a kill leaves `rolling-back`, which the next start resumes. Without it
            // a kill after the main file moved back would look like a healthy `pending` restore with the WAL stranded.
            writeDurably(marker, STATE_ROLLING_BACK)
            completeRollback(live)
            marker.delete()
            DatabaseHealth.reset()
            true
        } catch (t: Throwable) {
            DatabaseHealth.reset()
            DatabaseHealth.markFailed(
                "Your previous data could not be put back (${t.message ?: t.javaClass.simpleName}). " +
                    "It was kept safely; please send the data file to support.",
                supportCopy(context, dbName)
            )
            false
        }
    }

    /**
     * Does (or finishes) a rollback. While `.pre-restore` still exists the main file has not moved back yet, so every
     * live sidecar belongs to the restored file and is removed first; once it is gone the live sidecars are the
     * original's own and are never deleted.
     */
    private fun completeRollback(live: File) {
        if (File(live.path + PRE_RESTORE).exists()) {
            listOf("-wal", "-shm", "-journal").forEach { deleteIfExists(File(live.path + it)) }
        }
        restoreFromPre(live)
    }

    /** Moves the `.pre-restore` set over the live names, main file first. Throws, leaving the sources, on failure. */
    private fun restoreFromPre(live: File) {
        val preMain = File(live.path + PRE_RESTORE)
        if (preMain.exists()) moveReplacing(preMain, live)
        listOf("-wal", "-shm").forEach { suffix ->
            val pre = File(live.path + PRE_RESTORE + suffix)
            val target = File(live.path + suffix)
            // A sidecar with no `.pre-restore` counterpart is left alone: after an interrupted swap it is the
            // original's own WAL. (rollBack has already removed the restored file's sidecars.)
            if (pre.exists()) moveReplacing(pre, target)
        }
    }

    /** `pending` or `rolling-back` only when the marker says so; an empty, unreadable or unknown marker is the safe reading `swapping`. */
    private fun markerState(marker: File): String = stateOf(runCatching { marker.readText() }.getOrDefault(""))

    private fun stateOf(text: String): String =
        when (text.trim()) {
            STATE_PENDING -> STATE_PENDING
            STATE_ROLLING_BACK -> STATE_ROLLING_BACK
            STATE_UNDOING_SWAP -> STATE_UNDOING_SWAP
            else -> STATE_SWAPPING
        }

    /** Best copy of the original data to offer to support: the folded `.pre-restore` set, else the latest safety copy, else the live file. */
    fun supportCopy(context: Context, dbName: String): File? {
        val live = context.getDatabasePath(dbName)
        val pre = File(live.path + PRE_RESTORE)
        val preWal = File(pre.path + "-wal")
        val liveWal = File(live.path + "-wal")
        if (pre.exists()) {
            // A move may be half-done. The WAL that belongs to the original main is `.pre-restore-wal` or, when the
            // main moved aside first and the WAL did not follow, the live `-wal` (only while no live main exists, so it
            // cannot be the restored file's). The restored file's own WAL is never used.
            val wal = when {
                preWal.exists() -> preWal
                !live.exists() && liveWal.exists() -> liveWal
                else -> null
            }
            return foldedSupportCopy(pre, wal, pre)
        }
        // A rollback or swap-undo got as far as moving the original main back but not its WAL: only under those two
        // markers is `.pre-restore-wal` known to be the original's (otherwise it may be a stale one).
        val marker = File(live.path + MARKER)
        val state = if (marker.exists()) markerState(marker) else null
        if (live.exists() && preWal.exists() && (state == STATE_ROLLING_BACK || state == STATE_UNDOING_SWAP)) {
            return foldedSupportCopy(live, preWal, pre)
        }
        return PreMigrationGuard.latestSafetyCopy(context) ?: live.takeIf { it.exists() }
    }

    /**
     * A single self-contained file for support: copies `.pre-restore` and its `-wal` (if any) to a temp pair, folds the
     * WAL into the copy and returns `<pre>-support.db`. The `.pre-restore` set itself is never modified. If anything
     * fails the main `.pre-restore` file is returned instead.
     */
    fun preRestoreSupportCopy(preMain: File): File = foldedSupportCopy(preMain, File(preMain.path + "-wal"), preMain)

    /**
     * Folds [preMain] + [preWal] into `<namePrefix>-support.db` (a name the 14-day cleanup removes). The inputs are only
     * read. Returns [preMain] when there is no WAL to fold or anything fails (limitation: that file then lacks the
     * WAL's recent changes).
     */
    private fun foldedSupportCopy(preMain: File, preWal: File?, namePrefix: File): File {
        if (preWal == null || !preWal.exists() || preWal.length() == 0L) return preMain
        val finalCopy = File(namePrefix.path + "-support.db")
        val tmp = File(namePrefix.path + "-support.tmp")
        val tmpWal = File(tmp.path + "-wal")
        val tmpShm = File(tmp.path + "-shm")
        val tmpJournal = File(tmp.path + "-journal")
        fun clean() = listOf(tmp, tmpWal, tmpShm, tmpJournal).forEach { runCatching { it.delete() } }
        return try {
            clean()
            copyFile(preMain, tmp)
            copyFile(preWal, tmpWal)
            val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
                tmp.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE, android.database.DatabaseErrorHandler { }
            )
            try {
                sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use {
                    check(it.moveToFirst() && it.getInt(0) == 0) { "WAL checkpoint did not complete" }
                }
            } finally {
                runCatching { sqlite.close() }
            }
            tmpWal.delete()
            tmpShm.delete()
            check(tmp.length() > 0) { "Folded copy is empty" }
            if (finalCopy.exists()) check(finalCopy.delete()) { "Could not replace the old support copy" }
            check(tmp.renameTo(finalCopy)) { "Could not finalise the support copy" }
            finalCopy
        } catch (t: Throwable) {
            clean()
            preMain
        }
    }

    private fun copyFile(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    /**
     * Rename first (atomic, replaces the target on Android). Where that is refused, copy to a temp file next to the
     * target, fsync, verify the size and only then replace the target; the source is deleted last. Once the target has
     * been deleted the verified temp copy is never removed, even if the final rename fails: it is then the only
     * complete copy and its path is reported in the exception.
     */
    internal fun moveReplacing(source: File, target: File, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) {
        if (rename(source, target)) return
        val tmp = File(target.path + ".tmp")
        tmp.delete()
        var targetGone = false
        try {
            copyFile(source, tmp)
            check(tmp.length() == source.length()) { "Copy of ${source.name} is incomplete" }
            if (target.exists()) {
                check(target.delete()) { "Could not replace ${target.name}" }
                targetGone = true
            }
            check(rename(tmp, target)) { "Could not put ${target.name} in place; the complete copy was kept at ${tmp.path}" }
        } catch (t: Throwable) {
            if (!targetGone) tmp.delete()
            throw t
        }
        source.delete()
    }

    /** Temp file + fsync + rename, so the file is never truncated in place: it holds either the old or the new content. */
    private fun writeDurably(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            // Platforms whose rename will not replace (never Android): fall back to delete + rename.
            check(file.delete() && tmp.renameTo(file)) { "Could not write ${file.name}" }
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    /** Name marker of the undo set (`.pre-restore`, `-wal`, `-shm`), used by the 14-day cleanup. */
    const val PRE_RESTORE_MARKER = PRE_RESTORE
}
