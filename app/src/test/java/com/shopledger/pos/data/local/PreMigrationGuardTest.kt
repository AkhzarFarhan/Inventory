package com.shopledger.pos.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreMigrationGuardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "guard-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(name)
        context.getDatabasePath(name).parentFile?.listFiles { f -> f.name.contains(".pre-v") || f.name.contains(".corrupt-") }?.forEach { it.delete() }
    }

    private fun createDb(version: Int) {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE t (x INTEGER)")
            db.execSQL("INSERT INTO t VALUES (42)")
            db.version = version
        }
    }

    @Test
    fun olderDatabase_getsAReadableSafetyCopy() {
        createDb(2)
        val outcome = PreMigrationGuard.prepare(context, name, 3)
        assertTrue(outcome is PreMigrationGuard.Outcome.BackedUp)
        val copy = (outcome as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT x FROM t", null).use { c ->
                c.moveToFirst()
                assertEquals(42, c.getInt(0))
            }
        }
        assertEquals(copy, PreMigrationGuard.latestSafetyCopy(context))
    }

    @Test
    fun currentOrMissingDatabase_needsNoCopy() {
        assertEquals(PreMigrationGuard.Outcome.NotNeeded, PreMigrationGuard.prepare(context, "missing.db", 3))
        createDb(3)
        assertEquals(PreMigrationGuard.Outcome.NotNeeded, PreMigrationGuard.prepare(context, name, 3))
    }

    @Test
    fun oldSafetyCopies_areDeleted() {
        createDb(2)
        val copy = (PreMigrationGuard.prepare(context, name, 3) as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        copy.setLastModified(1_000L)
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, maxAgeMs = 14L * 86_400_000L, now = 30L * 86_400_000L)
        assertFalse(copy.exists())
    }

    @Test
    fun garbageHeader_neverThrows_keepsOriginalAndMakesEvidenceCopy() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val garbage = ByteArray(4096).also { java.util.Random(7).nextBytes(it) }
        // user_version (offset 60) = 0 (the random magic is rejected regardless; kept for clarity).
        for (i in 60..63) garbage[i] = 0
        file.writeBytes(garbage)

        val outcome = PreMigrationGuard.prepare(context, name, 3)

        assertTrue(outcome.toString(), outcome is PreMigrationGuard.Outcome.Failed || outcome is PreMigrationGuard.Outcome.Corrupt)
        assertTrue("original file must still exist", file.exists())
        assertTrue("original must be byte-for-byte unchanged", garbage.contentEquals(file.readBytes()))
        val evidence = when (outcome) {
            is PreMigrationGuard.Outcome.Failed -> outcome.evidenceCopy
            is PreMigrationGuard.Outcome.Corrupt -> outcome.evidenceCopy
            else -> null
        }
        assertTrue("evidence copy returned", evidence != null && evidence.exists())
        assertTrue(evidence!!.name.contains(".corrupt-"))
        assertTrue(garbage.contentEquals(evidence.readBytes()))
        assertTrue(file.parentFile!!.listFiles { f -> f.name.contains(".corrupt-") }!!.isNotEmpty())
    }

    @Test
    fun corruptCopies_areNotDeletedByCleanup() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val evidence = java.io.File(file.parentFile, "guard-test.corrupt-1.db")
        evidence.writeBytes(ByteArray(10))
        evidence.setLastModified(1_000L)
        PreMigrationGuard.deleteSafetyCopiesOlderThan(context, maxAgeMs = 1L, now = 30L * 86_400_000L)
        assertTrue(evidence.exists())
    }

    @Test
    fun currentDatabase_isNotModified() {
        createDb(3)
        val file = context.getDatabasePath(name)
        file.setLastModified(123_000L)
        val before = file.readBytes()
        val mtime = file.lastModified()
        assertEquals(PreMigrationGuard.Outcome.NotNeeded, PreMigrationGuard.prepare(context, name, 3))
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(mtime, file.lastModified())
        assertFalse(java.io.File(file.path + "-wal").exists())
    }

    @Test
    fun secondUpgradeAttempt_rewritesSafetyCopyWithFreshMtime() {
        createDb(2)
        val copy = (PreMigrationGuard.prepare(context, name, 3) as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        copy.setLastModified(1_000L)
        val again = (PreMigrationGuard.prepare(context, name, 3) as PreMigrationGuard.Outcome.BackedUp).safetyCopy
        assertEquals(copy, again)
        assertTrue("fresh mtime", again.lastModified() > 1_000L)
        assertEquals(context.getDatabasePath(name).length(), again.length())
        assertFalse(java.io.File(copy.path + ".tmp").exists())
    }

    @Test
    fun repeatedPrepareOnCorruptFile_reusesOneEvidenceCopy() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val garbage = ByteArray(4096).also { java.util.Random(11).nextBytes(it) }
        file.writeBytes(garbage)
        repeat(3) { PreMigrationGuard.prepare(context, name, 3) }
        val copies = file.parentFile!!.listFiles { f -> f.name.startsWith("guard-test.corrupt-") && f.name.endsWith(".db") }!!
        assertEquals(1, copies.size)
        assertTrue(garbage.contentEquals(file.readBytes()))
    }

    @Test
    fun nonSqliteMagicWithHighUserVersion_isCorruptNotNeeded() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        val bytes = ByteArray(4096) { 1 }
        bytes[63] = 5 // bytes 60..63 encode user_version 5 (>= 3)
        file.writeBytes(bytes)
        val outcome = PreMigrationGuard.prepare(context, name, 3)
        assertTrue(outcome.toString(), outcome is PreMigrationGuard.Outcome.Corrupt || outcome is PreMigrationGuard.Outcome.Failed)
        val evidence = when (outcome) {
            is PreMigrationGuard.Outcome.Failed -> outcome.evidenceCopy
            is PreMigrationGuard.Outcome.Corrupt -> outcome.evidenceCopy
            else -> null
        }
        assertTrue(evidence != null && evidence.exists())
        assertTrue(bytes.contentEquals(file.readBytes()))
    }
}
