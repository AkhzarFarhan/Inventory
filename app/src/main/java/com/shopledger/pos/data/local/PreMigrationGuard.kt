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
 * [prepare] never throws and never modifies or deletes the live database file.
 */
object PreMigrationGuard {
    private const val MARKER = ".pre-v"
    private const val CORRUPT_MARKER = ".corrupt-"
    private const val HEADER_SIZE = 100
    private const val USER_VERSION_OFFSET = 60L

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
        if (target.exists()) target.delete()
        check(tmp.renameTo(target)) { "Could not finalise safety copy" }
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

    /** Best-effort copy of the live file (and its -wal) for the recovery screen; never touches the original. */
    private fun copyEvidence(dbFile: File, dbName: String): File? = try {
        if (!dbFile.exists()) {
            null
        } else {
            val base = "${dbName.removeSuffix(".db")}$CORRUPT_MARKER${System.currentTimeMillis()}.db"
            val target = File(dbFile.parentFile, base)
            dbFile.copyTo(target, overwrite = true)
            val wal = File(dbFile.path + "-wal")
            if (wal.exists()) runCatching { wal.copyTo(File(target.path + "-wal"), overwrite = true) }
            target
        }
    } catch (t: Throwable) {
        null
    }

    private fun describe(t: Throwable): String = "${t.javaClass.simpleName}: ${t.message ?: "no detail"}"

    fun latestSafetyCopy(context: Context): File? =
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) && f.name.endsWith(".db") }?.maxByOrNull { it.lastModified() }

    /** Only `.pre-v` safety copies are removed; `.corrupt-` evidence copies are never touched. */
    fun deleteSafetyCopiesOlderThan(context: Context, maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) && !f.name.contains(CORRUPT_MARKER) }
            ?.filter { now - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    private fun databaseDir(context: Context): File? = context.getDatabasePath("probe").parentFile
}
