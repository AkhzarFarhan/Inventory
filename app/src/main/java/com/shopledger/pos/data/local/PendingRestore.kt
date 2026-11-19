package com.shopledger.pos.data.local

import android.content.Context
import java.io.File

/**
 * Second half of a restore. BackupManager only ever *stages* a validated backup as `<db>.restore-staged`; this swaps it
 * in at the start of a fresh process (before anything has the database open) and, once Room has opened and migrated it,
 * rebuilds everything derived. If that fails the original files are put back.
 *
 * The replaced database is kept as `<db>.pre-restore` (+ `-wal`/`-shm`) as a one-shot undo until the 14-day cleanup.
 */
object PendingRestore {
    private const val STAGED = ".restore-staged"
    private const val PRE_RESTORE = ".pre-restore"
    private const val MARKER = ".restore-pending"
    private val SUFFIXES = listOf("", "-wal", "-shm")

    sealed interface Outcome {
        data object NotPending : Outcome
        data class Restored(val violations: List<String>) : Outcome
        data class RolledBack(val reason: String) : Outcome
    }

    /** True while a swapped-in backup has not yet been confirmed by [finishOrRollback]. */
    fun isPending(context: Context, dbName: String): Boolean = File(context.getDatabasePath(dbName).path + MARKER).exists()

    /**
     * Swaps a staged backup in. Returns true if it did. On any failure the original files are put back, the staged file
     * is left for a later attempt and false is returned; the live data is never lost.
     */
    fun applyStaged(context: Context, dbName: String): Boolean {
        val live = context.getDatabasePath(dbName)
        val staged = File(live.path + STAGED)
        val marker = File(live.path + MARKER)
        if (!staged.exists()) return false
        // An earlier restore is still unconfirmed: its .pre-restore set is the only copy of the original data.
        if (marker.exists()) return false

        val moved = mutableListOf<Pair<File, File>>() // from live name -> pre-restore name
        var stagedInPlace = false
        try {
            SUFFIXES.forEach { deleteIfExists(File(live.path + PRE_RESTORE + it)) }
            for (suffix in SUFFIXES) {
                val from = File(live.path + suffix)
                if (!from.exists()) continue
                val to = File(live.path + PRE_RESTORE + suffix)
                if (!from.renameTo(to)) throw IllegalStateException("Could not move ${from.name} aside")
                moved += from to to
            }
            if (!staged.renameTo(live)) throw IllegalStateException("Could not move the staged backup into place")
            stagedInPlace = true
            marker.writeText("pending")
            // renameTo keeps the old modification time; restart the 14-day undo window from now.
            val now = System.currentTimeMillis()
            moved.forEach { (_, to) -> to.setLastModified(now) }
            return true
        } catch (t: Throwable) {
            runCatching {
                if (stagedInPlace) live.renameTo(staged)
                marker.delete()
                moved.asReversed().forEach { (from, to) ->
                    if (!to.renameTo(from)) from.delete().also { to.renameTo(from) }
                }
            }
            return false
        }
    }

    /**
     * If a restore is pending: open the database (running any migration), rebuild stock/party projections and the
     * outbox in one transaction and check the ledger invariants. If anything throws, [close] the database, put the
     * original files back and report [Outcome.RolledBack]; the caller then opens the database normally.
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
            val sqlite = open().openHelper.writableDatabase
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
            // If putting the files back fails, the marker and the .pre-restore set stay for the next launch to retry.
            val undone = runCatching { rollBack(live, marker, close) }
            Outcome.RolledBack(if (undone.isSuccess) reason else "$reason (restoring the previous data failed: ${undone.exceptionOrNull()?.message})")
        }
    }

    private fun rollBack(live: File, marker: File, close: () -> Unit) {
        runCatching(close)
        // The restored sidecars belong to the restored file; they must not be paired with the original.
        listOf("-wal", "-shm", "-journal").forEach { deleteIfExists(File(live.path + it)) }
        val preMain = File(live.path + PRE_RESTORE)
        if (preMain.exists()) {
            // rename replaces the target on Android; the delete only matters where it does not.
            if (!preMain.renameTo(live)) {
                live.delete()
                check(preMain.renameTo(live)) { "Could not put the previous data back" }
            }
            listOf("-wal", "-shm").forEach { suffix ->
                val pre = File(live.path + PRE_RESTORE + suffix)
                if (pre.exists()) pre.renameTo(File(live.path + suffix))
            }
        }
        marker.delete()
    }

    private fun deleteIfExists(file: File) {
        if (file.exists()) file.delete()
    }

    /** Name marker of the undo set (`.pre-restore`, `-wal`, `-shm`), used by the 14-day cleanup. */
    const val PRE_RESTORE_MARKER = PRE_RESTORE
}
