package com.shopledger.pos.core.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.OutboxRebuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class RestoreReport(val fromSchemaVersion: Int, val violations: List<String>)

/**
 * Whole-database backups. Works on API 24 (no VACUUM INTO): copies db + WAL while holding the
 * write lock, then folds the WAL into the copy and verifies it with PRAGMA integrity_check.
 */
class BackupManager(
    private val context: Context,
    private val cipher: SnapshotCipher,
    private val databaseProvider: () -> LpsDatabase,
    private val closeDatabase: () -> Unit,
    private val dbName: String = LpsDatabase.DB_NAME,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val snapshotDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    /** Encrypted, on-device only. Keeps the newest [keep]. */
    fun createEncryptedSnapshot(keep: Int = 7): File {
        val plain = copyConsistentDatabase()
        try {
            val out = File(snapshotDir, "snapshot-${stamp()}.slsnap")
            writeAtomically(out, cipher.encrypt(gzip(plain.readBytes())))
            listSnapshots().dropLast(keep).forEach { it.delete() }
            return out
        } finally {
            deleteWithSidecars(plain)
        }
    }

    /** Gzip only, so it can be restored on another phone. Lives in cache/backups for FileProvider sharing. */
    fun exportShareableBackup(): File {
        val plain = copyConsistentDatabase()
        try {
            val dir = File(context.cacheDir, "backups").apply { mkdirs() }
            val out = File(dir, "ShopLedger-backup-${stamp()}.slbak")
            writeAtomically(out, gzip(plain.readBytes()))
            return out
        } finally {
            deleteWithSidecars(plain)
        }
    }

    fun listSnapshots(): List<File> =
        snapshotDir.listFiles { f -> f.name.endsWith(".slsnap") }?.sortedBy { it.name }.orEmpty()

    fun restoreFromSnapshot(file: File): RestoreReport =
        restoreDatabaseBytes(decode { gunzip(cipher.decrypt(file.readBytes())) })

    fun restoreFromShareable(input: InputStream): RestoreReport =
        restoreDatabaseBytes(decode { gunzip(input.readBytes()) })

    private inline fun decode(block: () -> ByteArray): ByteArray =
        try {
            block()
        } catch (e: Exception) {
            throw BackupException("This file is not a ShopLedger backup", e)
        }

    private fun copyConsistentDatabase(): File {
        val sqlite = databaseProvider().openHelper.writableDatabase
        val live = context.getDatabasePath(dbName)
        val work = File(context.cacheDir, "snapshot-work").apply { mkdirs() }
        val copy = File(work, "copy-${clock()}.db")
        // Holding a write transaction blocks other writers and checkpoints, so db + wal are one consistent state.
        sqlite.beginTransaction()
        try {
            live.copyTo(copy, overwrite = true)
            val wal = File(live.path + "-wal")
            if (wal.exists()) wal.copyTo(File(copy.path + "-wal"), overwrite = true)
        } finally {
            sqlite.endTransaction()
        }
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READWRITE, DatabaseErrorHandler { }).use { snapshot ->
            snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            val integrity = snapshot.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
            if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("Snapshot failed its integrity check: $integrity")
        }
        File(copy.path + "-wal").delete()
        File(copy.path + "-shm").delete()
        return copy
    }

    private fun restoreDatabaseBytes(bytes: ByteArray): RestoreReport {
        val live = context.getDatabasePath(dbName)
        live.parentFile?.mkdirs()
        val staged = File(live.path + ".restore-staged")
        staged.writeBytes(bytes)
        val version = try {
            SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { candidate ->
                val integrity = candidate.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("The backup file is damaged ($integrity)")
                val looksRight = candidate.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'inventory_items'", null
                ).use { it.moveToFirst() }
                if (!looksRight) throw BackupException("This file is not a ShopLedger backup")
                candidate.version
            }
        } catch (e: BackupException) {
            staged.delete()
            throw e
        } catch (e: Exception) {
            staged.delete()
            throw BackupException("This file is not a ShopLedger backup", e)
        }
        if (version > LpsDatabase.SCHEMA_VERSION) {
            staged.delete()
            throw BackupException("This backup was made by a newer version of ShopLedger. Update the app first.")
        }

        closeDatabase()
        File(live.path + "-wal").delete()
        File(live.path + "-shm").delete()
        if (!staged.renameTo(live)) {
            staged.copyTo(live, overwrite = true)
            staged.delete()
        }

        // Reopening runs any needed migration (older backups); then rebuild everything derived.
        val db = databaseProvider()
        val sqlite = db.openHelper.writableDatabase
        db.runInTransaction {
            sqlite.execSQL(
                "UPDATE item_variants SET stockMilliUnits = " +
                    "(SELECT COALESCE(SUM(m.deltaMilliUnits), 0) FROM stock_movements m WHERE m.skuId = item_variants.id)"
            )
            sqlite.execSQL(
                "UPDATE parties SET currentBalancePaise = " +
                    "(SELECT COALESCE(SUM(e.amountDeltaPaise), 0) FROM party_ledger_entries e WHERE e.partyId = parties.id)"
            )
            sqlite.execSQL("DELETE FROM sync_outbox")
            OutboxRebuilder.enqueueEverything(sqlite, clock())
        }
        return RestoreReport(version, IntegrityChecker.findViolations(sqlite))
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.path + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(clock()))

    private fun deleteWithSidecars(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    companion object {
        fun forApp(context: Context): BackupManager {
            val app = context.applicationContext
            return BackupManager(
                context = app,
                cipher = KeystoreSnapshotCipher(),
                databaseProvider = { LpsDatabase.getInstance(app) },
                closeDatabase = { LpsDatabase.closeInstance() }
            )
        }
    }
}
