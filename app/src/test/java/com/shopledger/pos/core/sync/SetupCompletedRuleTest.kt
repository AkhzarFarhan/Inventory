package com.shopledger.pos.core.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.data.local.LpsDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Existing-install rule: an explicit flag wins; otherwise any earlier sign of use (seller name or other shop setting,
 * legacy settings, starter-catalog seeded flag, database file) means "existing" (wizard never shown); only a fully
 * untouched install is fresh.
 */
@Config(application = android.app.Application::class)
@RunWith(AndroidJUnit4::class)
class SetupCompletedRuleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before
    fun cleanSlate() {
        context.getDatabasePath(LpsDatabase.DB_NAME).delete()
        listOf("shopledger_sync_prefs", "lps_hardware_sync_prefs", "lps_seed_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun sync() = context.getSharedPreferences("shopledger_sync_prefs", Context.MODE_PRIVATE)

    @Test
    fun freshInstallIsNotCompleted() {
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
        // The fresh decision is stored, because the database file is created later in the same launch.
        assertTrue(sync().contains("setup_completed"))
        assertFalse(sync().getBoolean("setup_completed", true))
    }

    @Test
    fun freshInstallStaysFreshAfterTheDatabaseFileAppears() {
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
        val db = context.getDatabasePath(LpsDatabase.DB_NAME)
        db.parentFile?.mkdirs()
        db.writeText("x")
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
    }

    @Test
    fun existingSellerNameMeansCompletedAndIsPersisted() {
        sync().edit().putString("seller_name", "Sharma Hardware").commit()
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
        assertTrue(sync().getBoolean("setup_completed", false))
    }

    @Test
    fun otherStoredSettingsMeanExisting() {
        sync().edit().putString("owner_pin_hash", "abc").commit()
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
    }

    @Test
    fun seededCatalogFlagFromAnEarlierVersionMeansExisting() {
        context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE).edit().putBoolean("catalog_seeded_v1", true).commit()
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
    }

    @Test
    fun legacySettingsFileMeansExisting() {
        context.getSharedPreferences("lps_hardware_sync_prefs", Context.MODE_PRIVATE).edit().putString("seller_name", "Old Shop").commit()
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
    }

    @Test
    fun existingDatabaseFileMeansExisting() {
        val db = context.getDatabasePath(LpsDatabase.DB_NAME)
        db.parentFile?.mkdirs()
        db.writeText("x")
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
    }

    @Test
    fun explicitFalseFlagWinsOverOtherKeys() {
        sync().edit().putBoolean("setup_completed", false).putString("seller_name", "Half done").commit()
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
    }

    @Test
    fun saveSetupCompletedPersistsAndUpdatesTheFlow() {
        SyncManager.init(context)
        SyncManager.saveSetupCompleted(context, true)
        assertTrue(SyncManager.isSetupCompletedFlow.value)
        assertTrue(sync().getBoolean("setup_completed", false))
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
    }

    @Test
    fun ownerPinUsesTheExistingHashing() {
        SyncManager.init(context)
        SyncManager.saveOwnerPin(context, "4321")
        assertTrue(SyncManager.verifyOwnerPin("4321"))
        assertFalse(SyncManager.verifyOwnerPin("1234"))
    }
}
