package com.shopledger.pos.data.local

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Before Room upgrades the schema: verify the file and keep a byte-for-byte safety copy.
 * [prepare] never throws and never deletes or loses data in the live database file
 * (a WAL checkpoint may fold the WAL into it).
 */
object PreMigrationGuard {
    private const val MARKER = ".pre-v"
    private const val CORRUPT_MARKER = ".corrupt-"
    private const val HEADER_SIZE = 100
    private const val USER_VERSION_OFFSET = 60L
    private const val KEEP_EVIDENCE_COPIES = 2
    private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

    sealed interface Outcome {
        data object NotNeeded : Outcome
        data class BackedUp(val safetyCopy: File) : Outcome

        /** The file failed its integrity check. [evidenceCopy] is a best-effort copy for support, if one could be made. */
        data class Corrupt(val detail: String, val evidenceCopy: File? = null) : Outcome

        /** The check itself could not run (unreadable header, full disk, ...). Treated like corruption by the caller. */
        data class Failed(val detail: String, val evidenceCopy: File? = null) : Outcome
    }

    fun prepare(context: Context, dbName: String, targetVersion: Int): Outcome {
        val dbFile = try {
            context.getDatabasePath(dbName)
        } catch (t: Throwable) {
            return Outcome.Failed(describe(t))
        }
        return try {
            prepareInternal(dbFile, dbName, targetVersion)
        } catch (t: Throwable) {
            Outcome.Failed(describe(t), copyEvidence(dbFile, dbName))
        }
    }

    private fun prepareInternal(dbFile: File, dbName: String, targetVersion: Int): Outcome {
        if (!dbFile.exists()) return Outcome.NotNeeded
        // Cheap path: user_version is a 4-byte big-endian int at offset 60 of the header, so an up-to-date
        // file is recognised without opening it (keeps Application.onCreate fast).
        if (dbFile.length() >= HEADER_SIZE && !hasSqliteMagic(dbFile)) {
            return Outcome.Corrupt("Not a SQLite database (bad header magic)", copyEvidence(dbFile, dbName))
        }
        headerVersion(dbFile)?.let { if (it >= targetVersion) return Outcome.NotNeeded }

        // A no-op error handler: Android's default handler would DELETE a corrupt file.
        val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        try {
            if (sqlite.version >= targetVersion) return Outcome.NotNeeded
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val result = sqlite.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "no result" }
            if (!result.equals("ok", ignoreCase = true)) return Outcome.Corrupt(result, copyEvidence(dbFile, dbName))
        } finally {
            runCatching { sqlite.close() }
        }
        val copy = File(dbFile.parentFile, "${dbName.removeSuffix(".db")}$MARKER$targetVersion.db")
        writeFreshCopy(dbFile, copy)
        return Outcome.BackedUp(copy)
    }

    private fun hasSqliteMagic(dbFile: File): Boolean = RandomAccessFile(dbFile, "r").use { raf ->
        val buf = ByteArray(SQLITE_MAGIC.size)
        raf.readFully(buf)
        buf.contentEquals(SQLITE_MAGIC)
    }

    /** Returns the header's user_version, or null when the file is too short to have one. */
    private fun headerVersion(dbFile: File): Int? {
        if (dbFile.length() < HEADER_SIZE) return null
        return RandomAccessFile(dbFile, "r").use { raf ->
            raf.seek(USER_VERSION_OFFSET)
            raf.readInt()
        }
    }

    /** Always a new copy (restarts the retention window): tmp file, fsync, size check, then rename into place. */
    private fun writeFreshCopy(source: File, target: File) {
        val tmp = File(target.path + ".tmp")
        tmp.delete()
        copyDurably(source, tmp)
        check(tmp.length() == source.length()) { "Safety copy size mismatch" }
        check(tmp.renameTo(target) || (target.delete() && tmp.renameTo(target))) { "Could not finalise safety copy" }
    }

    private fun copyDurably(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    /**
     * Best-effort copy of the live file (and its -wal) for the recovery screen; never touches the original.
     * Reuses an existing up-to-date evidence copy, deletes partial copies on failure, and keeps only the newest
     * [KEEP_EVIDENCE_COPIES] guard evidence copies for this database.
     */
    private fun copyEvidence(dbFile: File, dbName: String): File? {
        var target: File? = null
        return try {
            if (!dbFile.exists()) return null
            val prefix = "${dbName.removeSuffix(".db")}$CORRUPT_MARKER"
            val dir = dbFile.parentFile
            fun existing(): List<File> = dir?.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".db") }
                ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenByDescending { it.name })
                .orEmpty()
            existing().firstOrNull { it.length() == dbFile.length() && it.lastModified() >= dbFile.lastModified() }
                ?.let { return it }
            val fresh = File(dir, "$prefix${System.currentTimeMillis()}.db")
            target = fresh
            dbFile.copyTo(fresh, overwrite = true)
            val wal = File(dbFile.path + "-wal")
            if (wal.exists()) runCatching { wal.copyTo(File(fresh.path + "-wal"), overwrite = true) }
            existing().filter { it != fresh }.drop(KEEP_EVIDENCE_COPIES - 1).forEach { old ->
                old.delete()
                File(old.path + "-wal").delete()
            }
            fresh
        } catch (t: Throwable) {
            target?.let { runCatching { it.delete() }; runCatching { File(it.path + "-wal").delete() } }
            null
        }
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"

    /** Newest `.pre-v` copy; when there is none, the newest `.pre-restore` database (the original data of a restore). */
    fun latestSafetyCopy(context: Context): File? {
        val dir = databaseDir(context) ?: return null
        return dir.listFiles { f -> f.name.contains(MARKER) && f.name.endsWith(".db") }?.maxByOrNull { it.lastModified() }
            ?: dir.listFiles { f -> f.isFile && f.name.endsWith(PendingRestore.PRE_RESTORE_MARKER) }?.maxByOrNull { it.lastModified() }
                ?.let { PendingRestore.preRestoreSupportCopy(it) }
    }

    /** Only `.pre-v` safety copies and `.pre-restore` undo sets are removed; `.corrupt-` evidence copies and the live db are never touched. */
    fun deleteSafetyCopiesOlderThan(
        context: Context,
        maxAgeMs: Long,
        now: Long = System.currentTimeMillis(),
        dbName: String = LpsDatabase.DB_NAME
    ) {
        // Leftover temp files of interrupted moves/marker writes (exact names only). `<db>.tmp` / `<db>-wal.tmp` can be the
        // only complete copy after a failed move, so they are kept unless the live database exists and no restore is open.
        val tempsSafeToDelete = context.getDatabasePath(dbName).exists() && !PendingRestore.isPending(context, dbName)
        // While a restore marker exists the `.pre-restore*` set may be the only copy of the original data: keep it all.
        val restoreOpen = PendingRestore.isPending(context, dbName)
        val leftoverTemps = setOf(
            "$dbName.tmp", "$dbName-wal.tmp", "$dbName-shm.tmp", "$dbName.restore-staged.go.tmp", "$dbName.restore-pending.tmp"
        )
        databaseDir(context)?.listFiles { f ->
            !f.name.contains(CORRUPT_MARKER) && (
                f.name.contains(MARKER) || (!restoreOpen && f.name.contains(PendingRestore.PRE_RESTORE_MARKER)) ||
                    (tempsSafeToDelete && f.name in leftoverTemps)
                )
        }
            ?.filter { now - it.lastModified() > maxAgeMs }
            // Sidecars before the main file, so a kill never leaves a main without its WAL looking complete.
            ?.sortedBy { if (it.name.endsWith("-wal") || it.name.endsWith("-shm")) 0 else 1 }
            ?.forEach { it.delete() }
    }

    private fun databaseDir(context: Context): File? = context.getDatabasePath("probe").parentFile
}
