package com.shopledger.pos

import android.app.Application
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.backup.BackupManager
import com.shopledger.pos.core.backup.LocalSnapshotWorker
import com.shopledger.pos.core.draft.ItemDraftManager
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.DatabaseHealth
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import com.shopledger.pos.data.local.PreMigrationGuard
import com.shopledger.pos.data.repository.LpsRepository
import java.util.concurrent.TimeUnit

/**
 * Lightweight Application class using deterministic Manual Dependency Injection (`appContainer`)
 * to achieve <280ms cold start and zero reflection overhead on old low-RAM Android devices.
 */
class ShopLedgerApp : Application() {

    lateinit var database: LpsDatabase
        private set

    lateinit var backupManager: BackupManager
        private set

    lateinit var repository: LpsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        AppErrorManager.installGlobalExceptionHandler(this)
        runCatching { ItemDraftManager.init(this) }
            .onFailure { AppErrorManager.reportError("Failed to restore saved item draft", it) }
        runCatching { GoogleAuthManager.init(this) }
            .onFailure { AppErrorManager.reportError("Auth initialization issue", it) }
        runCatching { SyncManager.init(this) }
            .onFailure { AppErrorManager.reportError("Settings initialization issue", it) }
        database = LpsDatabase.getInstance(this)
        // getInstance has already swapped in a staged restore (if any); the marker says it still needs confirming.
        val restoring = PendingRestore.isPending(this, LpsDatabase.DB_NAME)
        if (PendingRestore.takeDiscardedNotice()) {
            AppErrorManager.reportWarning("Restore cancelled", "An unfinished restore was discarded. Your data was not changed.")
        }
        if (restoring) {
            // Always resolve a pending restore first: any failure caused by the restored file (open, migration,
            // health flagged by the guard or the corruption handler) rolls back to the original data.
            when (
                val outcome = PendingRestore.finishOrRollback(
                    this, LpsDatabase.DB_NAME, { LpsDatabase.getInstance(this) }, { LpsDatabase.closeInstance() }
                )
            ) {
                is PendingRestore.Outcome.Restored ->
                    if (outcome.violations.isEmpty()) {
                        AppErrorManager.reportSuccess("Backup restored", "Your data was replaced by the backup.")
                    } else {
                        AppErrorManager.reportWarning(
                            "Backup restored, but it has inconsistencies",
                            outcome.violations.take(3).joinToString("\n")
                        )
                    }
                is PendingRestore.Outcome.RolledBack -> {
                    // Health was reset when the original came back; only a failure of the original shows recovery.
                    database = LpsDatabase.getInstance(this)
                    if (outcome.originalBack && DatabaseHealth.state.value is DatabaseHealth.State.Ok) {
                        runCatching { database.openHelper.writableDatabase }.onFailure { markUpgradeFailed(it) }
                        AppErrorManager.reportError(
                            "Restore failed",
                            customDetail = "The backup could not be opened. Your previous data was kept."
                        )
                    }
                }
                // Room was not opened and health is Failed with a support copy; the recovery screen takes over.
                is PendingRestore.Outcome.NeedsSupport -> Unit
                PendingRestore.Outcome.NotPending -> Unit
            }
            database = LpsDatabase.getInstance(this)
        } else {
            if (DatabaseHealth.state.value is DatabaseHealth.State.Ok) {
                // Open (and migrate) now, so a failed upgrade shows the recovery screen instead of a random crash later.
                runCatching { database.openHelper.writableDatabase }.onFailure { markUpgradeFailed(it) }
            }
        }
        repository = LpsRepository(this, database)
        backupManager = BackupManager.forApp(this)
        if (DatabaseHealth.state.value is DatabaseHealth.State.Ok) {
            PreMigrationGuard.deleteSafetyCopiesOlderThan(this, TimeUnit.DAYS.toMillis(14))
            runCatching { SyncManager.scheduleDeltaSync(this) }
                .onFailure { AppErrorManager.reportError("Background sync scheduling failed", it) }
            runCatching { LocalSnapshotWorker.schedule(this) }
                .onFailure { AppErrorManager.reportError("Backup scheduling failed", it) }
            if (BuildConfig.DEBUG) verifyLedgerInBackground()
        }
    }

    private fun markUpgradeFailed(error: Throwable) {
        DatabaseHealth.markFailed(
            "Upgrading your data failed (${error.message ?: error.javaClass.simpleName}). Your data is safe.",
            PreMigrationGuard.latestSafetyCopy(this)
        )
    }

    /** Debug builds only: loudly report any ledger/projection drift so bugs surface during development. */
    private fun verifyLedgerInBackground() {
        Thread {
            runCatching { IntegrityChecker.findViolations(database.openHelper.readableDatabase) }
                .onSuccess { violations ->
                    if (violations.isNotEmpty()) {
                        AppErrorManager.reportWarning("Ledger check failed (debug)", violations.take(3).joinToString("\n"))
                    }
                }
                .onFailure { AppErrorManager.reportError("Ledger check crashed (debug)", it) }
        }.start()
    }
}
