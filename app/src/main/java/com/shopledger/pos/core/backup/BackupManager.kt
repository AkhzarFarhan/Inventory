package com.shopledger.pos.core.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import com.shopledger.pos.data.local.LpsDatabase
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Whole-database backups. Works on API 24 (no VACUUM INTO): copies db + WAL while holding the
 * write lock, then folds the WAL into the copy and verifies it with PRAGMA integrity_check.
 *
 * Restoring is two-phase and never touches the live database while the app runs: `stageRestore...` decodes and
 * validates a backup into `<db>.restore-staged`, and `PendingRestore` swaps it in on the next process start.
 *
 * Snapshot, export and staging are mutually exclusive across every BackupManager instance (the nightly worker
 * builds its own) through one process-wide lock.
 */
class BackupManager(
    private val context: Context,
    private val cipher: SnapshotCipher,
    private val databaseProvider: () -> LpsDatabase,
    private val dbName: String = LpsDatabase.DB_NAME,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val snapshotDir: File
        get() = File(context.filesDir, "backups").apply { mkdirs() }

    private val workDir: File
        get() = File(context.cacheDir, "snapshot-work")

    /** Encrypted, on-device only. Keeps the newest [keep] by sequence number (never by wall clock). */
    fun createEncryptedSnapshot(keep: Int = 7): File = synchronized(LOCK) {
        val plain = copyConsistentDatabase()
        try {
            val seq = (indexedSnapshots().maxOfOrNull { it.seq.coerceAtLeast(0) } ?: 0) + 1
            val out = File(snapshotDir, "snapshot-%06d-%s.slsnap".format(Locale.US, seq, stamp()))
            writeAtomically(out) { sink ->
                GZIPOutputStream(cipher.encrypt(sink)).use { gz -> FileInputStream(plain).use { it.copyTo(gz) } }
            }
            listSnapshots().dropLast(keep).forEach { it.delete() }
            out
        } finally {
            deleteWithSidecars(plain)
            workDir.deleteRecursively()
        }
    }

    /**
     * Gzip only, so it can be restored on another phone. Lives in cache/backups for FileProvider sharing;
     * older exports there are deleted so only the newest remains.
     */
    fun exportShareableBackup(): File = synchronized(LOCK) {
        val plain = copyConsistentDatabase()
        try {
            val dir = File(context.cacheDir, "backups").apply { mkdirs() }
            val out = File(dir, "ShopLedger-backup-${stamp()}.slbak")
            writeAtomically(out) { sink ->
                GZIPOutputStream(sink).use { gz -> FileInputStream(plain).use { it.copyTo(gz) } }
            }
            dir.listFiles { f -> f.isFile && f.name.startsWith("ShopLedger-backup-") && f.name.endsWith(".slbak") && f != out }
                ?.forEach { it.delete() }
            out
        } finally {
            deleteWithSidecars(plain)
            workDir.deleteRecursively()
        }
    }

    /** Oldest first. Snapshots are ordered by their sequence number; legacy timestamp-named files come before all of those. */
    fun listSnapshots(): List<File> = indexedSnapshots().map { it.file }

    fun latestSnapshot(): File? = listSnapshots().lastOrNull()

    private class Snapshot(val file: File, val seq: Int)

    private fun indexedSnapshots(): List<Snapshot> =
        snapshotDir.listFiles { f -> f.isFile && f.name.endsWith(".slsnap") }
            ?.map { f -> Snapshot(f, SEQ_NAME.matchEntire(f.name)?.groupValues?.get(1)?.toInt() ?: -1) }
            ?.sortedWith(compareBy<Snapshot> { it.seq }.thenBy { it.file.name })
            .orEmpty()

    /**
     * Decodes a shareable (gzip) backup, validates it and leaves it as `<db>.restore-staged` for the next start.
     * Does not touch the live database. Returns the backup's schema version.
     */
    fun stageRestoreFromShareable(input: InputStream): Int = stage { sink ->
        val buffered = BufferedInputStream(input)
        buffered.mark(2)
        val first = buffered.read()
        val second = buffered.read()
        buffered.reset()
        if (first != GZIP_MAGIC_1 || second != GZIP_MAGIC_2) throw BackupException(NOT_A_BACKUP)
        gunzipTo(buffered, sink)
    }

    /** Same as [stageRestoreFromShareable] for an encrypted on-device snapshot. */
    fun stageRestoreFromSnapshot(file: File): Int = stage { sink ->
        FileInputStream(file).use { raw -> gunzipTo(cipher.decrypt(raw), sink) }
    }

    private fun stage(decode: (OutputStream) -> Unit): Int = synchronized(LOCK) {
        val live = context.getDatabasePath(dbName)
        live.parentFile?.mkdirs()
        val tmp = File(live.path + ".restore-staged.tmp")
        val staged = File(live.path + ".restore-staged")
        var done = false
        try {
            deleteWithSidecars(tmp)
            try {
                SyncOnCloseOutputStream(FileOutputStream(tmp)).use { decode(it) }
            } catch (e: BackupException) {
                throw e
            } catch (e: Exception) {
                throw BackupException("The backup file is damaged or incomplete", e)
            }
            val version = validate(tmp)
            // Validation may have created -wal/-shm/-journal next to the tmp file; the staged file must stand alone.
            listOf("-wal", "-shm", "-journal").forEach { File(tmp.path + it).delete() }
            if (staged.exists() && !staged.delete()) throw BackupException("Could not replace the earlier staged backup")
            if (!tmp.renameTo(staged)) throw BackupException("Could not stage the backup (storage problem)")
            done = true
            version
        } finally {
            if (!done) deleteWithSidecars(tmp)
        }
    }

    private fun gunzipTo(source: InputStream, sink: OutputStream) {
        var total = 0L
        try {
            GZIPInputStream(source).use { gz ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = gz.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DECOMPRESSED_BYTES) throw BackupException("This backup is too large to restore")
                    sink.write(buffer, 0, n)
                }
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: IOException) {
            throw BackupException("The backup file is damaged or incomplete", e)
        }
    }

    /** Checks the candidate read-only and returns its schema version, or throws a specific [BackupException]. */
    private fun validate(candidate: File): Int {
        if (candidate.length() < SQLITE_HEADER_BYTES || !hasSqliteMagic(candidate)) throw BackupException(NOT_A_BACKUP)
        try {
            SQLiteDatabase.openDatabase(candidate.path, null, SQLiteDatabase.OPEN_READONLY, DatabaseErrorHandler { }).use { db ->
                val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("The backup file is damaged ($integrity)")
                fun hasTable(name: String) = db.rawQuery(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(name)
                ).use { it.moveToFirst() }
                if (!hasTable("inventory_items") || !hasTable("room_master_table")) throw BackupException(NOT_A_BACKUP)
                val version = db.version
                if (version > LpsDatabase.SCHEMA_VERSION) {
                    throw BackupException("This backup was made by a newer version of ShopLedger. Update the app first.")
                }
                if (version < 1) throw BackupException(NOT_A_BACKUP)
                return version
            }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            throw BackupException("The backup file is damaged", e)
        }
    }

    private fun hasSqliteMagic(file: File): Boolean = RandomAccessFile(file, "r").use { raf ->
        val buf = ByteArray(SQLITE_MAGIC.size)
        raf.readFully(buf)
        buf.contentEquals(SQLITE_MAGIC)
    }

    private fun copyConsistentDatabase(): File {
        workDir.deleteRecursively()
        workDir.mkdirs()
        val copy = File(workDir, "copy-${clock()}.db")
        try {
            val sqlite = databaseProvider().openHelper.writableDatabase
            val live = context.getDatabasePath(dbName)
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
                snapshot.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                    if (!c.moveToFirst()) throw BackupException("The backup could not be folded into one file")
                    val busy = c.getInt(0)
                    val log = c.getInt(1)
                    val checkpointed = c.getInt(2)
                    if (busy != 0 || log != checkpointed) {
                        throw BackupException("The backup copy was not fully written (busy=$busy, log=$log, checkpointed=$checkpointed)")
                    }
                }
                val integrity = snapshot.rawQuery("PRAGMA integrity_check", null).use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                if (!integrity.equals("ok", ignoreCase = true)) throw BackupException("Snapshot failed its integrity check: $integrity")
            }
            File(copy.path + "-wal").delete()
            File(copy.path + "-shm").delete()
            return copy
        } catch (t: Throwable) {
            deleteWithSidecars(copy)
            workDir.deleteRecursively()
            throw t
        }
    }

    /** Writes through `<target>.tmp` (fsynced), renames into place, and never leaves the tmp behind on failure. */
    private fun writeAtomically(target: File, write: (OutputStream) -> Unit) {
        val tmp = File(target.path + ".tmp")
        var done = false
        try {
            SyncOnCloseOutputStream(FileOutputStream(tmp)).use { write(it) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw IOException("Could not finalise ${target.name}")
            done = true
        } finally {
            if (!done) tmp.delete()
        }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(clock()))

    private fun deleteWithSidecars(file: File) {
        listOf("", "-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
    }

    /** Flushes and fsyncs the file when closed (idempotent), so a finished backup survives a power cut. */
    private class SyncOnCloseOutputStream(private val file: FileOutputStream) : FilterOutputStream(file) {
        private var closed = false

        override fun write(b: ByteArray, off: Int, len: Int) = file.write(b, off, len)

        override fun close() {
            if (closed) return
            closed = true
            try {
                file.flush()
                file.fd.sync()
            } finally {
                file.close()
            }
        }
    }

    companion object {
        /** One lock for every snapshot, export and staging operation in this process. */
        private val LOCK = Any()
        private const val MAX_DECOMPRESSED_BYTES = 1L shl 30
        private const val NOT_A_BACKUP = "This file is not a ShopLedger backup"
        private const val GZIP_MAGIC_1 = 0x1f
        private const val GZIP_MAGIC_2 = 0x8b
        private const val SQLITE_HEADER_BYTES = 100L
        private val SQLITE_MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        private val SEQ_NAME = Regex("""snapshot-(\d{6})-\d{8}-\d{6}-\d{3}\.slsnap""")

        fun forApp(context: Context): BackupManager {
            val app = context.applicationContext
            return BackupManager(
                context = app,
                cipher = KeystoreSnapshotCipher(),
                databaseProvider = { LpsDatabase.getInstance(app) }
            )
        }
    }
}
