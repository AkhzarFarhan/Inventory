package com.shopledger.pos.data.local

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Before Room upgrades the schema: verify the file and keep a byte-for-byte safety copy. */
object PreMigrationGuard {
    private const val MARKER = ".pre-v"

    sealed interface Outcome {
        data object NotNeeded : Outcome
        data class BackedUp(val safetyCopy: File) : Outcome
        data class Corrupt(val detail: String) : Outcome
    }

    fun prepare(context: Context, dbName: String, targetVersion: Int): Outcome {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) return Outcome.NotNeeded
        // A no-op error handler: Android's default handler would DELETE a corrupt file.
        val sqlite = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { })
        try {
            if (sqlite.version >= targetVersion) return Outcome.NotNeeded
            sqlite.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val result = sqlite.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "no result" }
            if (!result.equals("ok", ignoreCase = true)) return Outcome.Corrupt(result)
        } finally {
            sqlite.close()
        }
        val copy = File(dbFile.parentFile, "${dbName.removeSuffix(".db")}$MARKER$targetVersion.db")
        if (!copy.exists()) {
            val tmp = File(copy.path + ".tmp")
            dbFile.copyTo(tmp, overwrite = true)
            check(tmp.renameTo(copy)) { "Could not finalise safety copy" }
        }
        return Outcome.BackedUp(copy)
    }

    fun latestSafetyCopy(context: Context): File? =
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) && f.name.endsWith(".db") }?.maxByOrNull { it.lastModified() }

    fun deleteSafetyCopiesOlderThan(context: Context, maxAgeMs: Long, now: Long = System.currentTimeMillis()) {
        databaseDir(context)?.listFiles { f -> f.name.contains(MARKER) }
            ?.filter { now - it.lastModified() > maxAgeMs }
            ?.forEach { it.delete() }
    }

    private fun databaseDir(context: Context): File? = context.getDatabasePath("probe").parentFile
}
