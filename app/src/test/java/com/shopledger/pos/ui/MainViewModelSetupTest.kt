package com.shopledger.pos.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.repository.LpsRepository
import com.shopledger.pos.testing.newTestDatabase
import com.shopledger.pos.testing.scalar
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** First-run gating: a fresh install seeds nothing until the wizard decides; finishing is ordered and crash-safe. */
@Config(application = android.app.Application::class)
@RunWith(AndroidJUnit4::class)
class MainViewModelSetupTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: LpsDatabase

    @Before
    fun setUp() {
        context.getDatabasePath(LpsDatabase.DB_NAME).delete()
        listOf("shopledger_sync_prefs", "lps_hardware_sync_prefs", "lps_seed_prefs").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        db = newTestDatabase()
    }

    @After
    fun tearDown() = db.close()

    private fun seedPrefs() = context.getSharedPreferences("lps_seed_prefs", Context.MODE_PRIVATE)
    private fun itemCount() = db.scalar("SELECT COUNT(*) FROM inventory_items")

    private fun newViewModel() = MainViewModel(LpsRepository(context, db))

    private fun settle(ms: Long = 400) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("Timed out waiting for $what")
    }

    @Test
    fun freshInstallInitDoesNotSeed() {
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
        newViewModel()
        settle()
        assertEquals(0L, itemCount())
        assertFalse(seedPrefs().contains("catalog_seeded_v1"))
    }

    @Test
    fun completedInstallSeedsOnInit() {
        SyncManager.init(context)
        SyncManager.saveSetupCompleted(context, true)
        newViewModel()
        awaitUntil("starter catalog") { itemCount() > 0 }
    }

    @Test
    fun startEmptyMarksSeedDoneAndLaterInitsNeverSeed() {
        SyncManager.init(context)
        val vm = newViewModel()
        vm.finishSetup(false) { SyncManager.saveSetupCompleted(context, true) }
        awaitUntil("setup completed") { SyncManager.isSetupCompleted() && !vm.setupSaving.value }
        assertTrue(seedPrefs().getBoolean("catalog_seeded_v1", false))
        assertEquals(0L, itemCount())

        // Next launch: flag persisted, init runs again, a new ViewModel must still not seed.
        SyncManager.init(context)
        assertTrue(SyncManager.isSetupCompleted())
        newViewModel()
        settle()
        assertEquals(0L, itemCount())
    }

    @Test
    fun sampleDataIsSeededBeforeCompletedFlips() {
        SyncManager.init(context)
        val vm = newViewModel()
        var itemsWhenFlipping = -1L
        var seededFlagWhenFlipping = false
        var completedWhenApplying = true
        vm.finishSetup(true) {
            itemsWhenFlipping = itemCount()
            seededFlagWhenFlipping = seedPrefs().getBoolean("catalog_seeded_v1", false)
            completedWhenApplying = SyncManager.isSetupCompleted()
            SyncManager.saveSetupCompleted(context, true)
        }
        awaitUntil("setup completed") { SyncManager.isSetupCompleted() && !vm.setupSaving.value }
        assertTrue("items seeded before the flag flips", itemsWhenFlipping > 0)
        assertTrue(seededFlagWhenFlipping)
        assertFalse(completedWhenApplying)
        val count = itemCount()
        // A later init never seeds again.
        SyncManager.init(context)
        newViewModel()
        settle()
        assertEquals(count, itemCount())
    }

    @Test
    fun failedFinishKeepsSetupOpenAndReEnablesTheButtons() {
        SyncManager.init(context)
        val vm = newViewModel()
        vm.finishSetup(false) { throw IllegalStateException("disk full") }
        awaitUntil("saving to end") { !vm.setupSaving.value }
        assertFalse(SyncManager.isSetupCompleted())
        // The stored flag is still false, so a restart shows the wizard again.
        SyncManager.init(context)
        assertFalse(SyncManager.isSetupCompleted())
    }
}
