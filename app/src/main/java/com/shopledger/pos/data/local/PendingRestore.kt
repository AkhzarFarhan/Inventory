package com.shopledger.pos.data.local

import android.content.Context
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
    private val SUFFIXES = listOf("", "-wal", "-shm")

    /** A staged file is only applied if the user's restart happened within this long of staging. */
    const val TOKEN_MAX_AGE_MS = 10 * 60 * 1000L
    private const val CLOCK_SKEW_MS = 60 * 1000L

    private val discardedNotice = AtomicBoolean(false)

    sealed interface Outcome {
        data object NotPending : Outcome
        data class Restored(val violations: List<String>) : Outcome

        /** [originalBack] is false only when putting the previous files back failed; then health is Failed. */
        data class RolledBack(val reason: String, val originalBack: Boolean = true) : Outcome
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

    /**
     * Swaps a staged backup in. Returns true if it did. On any failure the original files are put back, the staged file
     * is left for a later attempt and false is returned; the live data is never lost.
     *
     * Order matters for a process kill at any point: the intent marker is written before any file moves, the previous
     * `.pre-restore` set is only deleted while the live database is present and no marker exists, and a half-done swap
     * found at the start is finished or undone before anything else.
     */
    fun applyStaged(context: Context, dbName: String, now: Long = System.currentTimeMillis()): Boolean {
        val live = context.getDatabasePath(dbName)
        val staged = File(live.path + STAGED)
        val token = File(live.path + TOKEN)
        val marker = File(live.path + MARKER)
        val preMain = File(live.path + PRE_RESTORE)

        // 1. Finish or undo an earlier interrupted swap before anything else.
        if (!live.exists() && preMain.exists()) {
            // The original was moved aside and the swap never completed: put it back (this also drops the marker).
            try {
                restoreFromPre(live)
                marker.delete()
            } catch (t: Throwable) {
                return false // both sets stay exactly where they are
            }
        } else if (marker.exists()) {
            val state = runCatching { marker.readText().trim() }.getOrDefault("")
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

        val moved = mutableListOf<Pair<File, File>>() // from live name -> pre-restore name
        var stagedInPlace = false
        try {
            // 3. Intent log first, then move files.
            writeDurably(marker, STATE_SWAPPING)
            // The live file is present (or there is nothing to protect), so an older undo set may go.
            if (live.exists()) SUFFIXES.forEach { deleteIfExists(File(live.path + PRE_RESTORE + it)) }
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
        return try {
            val database = open()
            failIfUnhealthy()
            val sqlite = database.openHelper.writableDatabase
            failIfUnhealthy()
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
                sqlite.setTransactionSuccessful()
            } finally {
                sqlite.endTransaction()
            }
            val violations = IntegrityChecker.findViolations(sqlite)
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
            // The restored sidecars belong to the restored file; they must not be paired with the original.
            listOf("-wal", "-shm", "-journal").forEach { deleteIfExists(File(live.path + it)) }
            restoreFromPre(live)
            marker.delete()
            DatabaseHealth.reset()
            true
        } catch (t: Throwable) {
            DatabaseHealth.reset()
            DatabaseHealth.markFailed(
                "Your previous data could not be put back (${t.message ?: t.javaClass.simpleName}). " +
                    "It was kept safely; please send the data file to support.",
                preMain.takeIf { it.exists() } ?: live.takeIf { it.exists() }
            )
            false
        }
    }

    /** Moves the `.pre-restore` set over the live names, main file first. Throws, leaving the sources, on failure. */
    private fun restoreFromPre(live: File) {
        val preMain = File(live.path + PRE_RESTORE)
        if (preMain.exists()) moveReplacing(preMain, live)
        listOf("-wal", "-shm").forEach { suffix ->
            val pre = File(live.path + PRE_RESTORE + suffix)
            val target = File(live.path + suffix)
            if (pre.exists()) moveReplacing(pre, target) else deleteIfExists(target)
        }
    }

    /**
     * Rename first (atomic, replaces the target on Android). Where that is refused, copy to a temp file next to the
     * target, fsync, verify the size and only then replace the target; the source is deleted last.
     */
    private fun moveReplacing(source: File, target: File) {
        if (source.renameTo(target)) return
        val tmp = File(target.path + ".tmp")
        tmp.delete()
        try {
            FileInputStream(source).use { input ->
                FileOutputStream(tmp).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            check(tmp.length() == source.length()) { "Copy of ${source.name} is incomplete" }
            if (target.exists()) check(target.delete()) { "Could not replace ${target.name}" }
            check(tmp.renameTo(target)) { "Could not put ${target.name} in place" }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
        source.delete()
    }

    private fun writeDurably(file: File, text: String) {
        FileOutputStream(file).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    /** Name marker of the undo set (`.pre-restore`, `-wal`, `-shm`), used by the 14-day cleanup. */
    const val PRE_RESTORE_MARKER = PRE_RESTORE
}
