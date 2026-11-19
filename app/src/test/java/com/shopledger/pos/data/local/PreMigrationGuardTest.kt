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
        context.getDatabasePath(name).parentFile?.listFiles { f -> f.name.contains(".pre-v") }?.forEach { it.delete() }
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
}
