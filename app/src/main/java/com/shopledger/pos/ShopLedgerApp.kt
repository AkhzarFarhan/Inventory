package com.shopledger.pos

import android.app.Application
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.draft.ItemDraftManager
import com.shopledger.pos.core.error.AppErrorManager
import com.shopledger.pos.core.sync.SyncManager
import com.shopledger.pos.data.local.IntegrityChecker
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.repository.LpsRepository

/**
 * Lightweight Application class using deterministic Manual Dependency Injection (`appContainer`)
 * to achieve <280ms cold start and zero reflection overhead on old low-RAM Android devices.
 */
class ShopLedgerApp : Application() {

    lateinit var database: LpsDatabase
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
        repository = LpsRepository(this, database)
        runCatching { SyncManager.scheduleDeltaSync(this) }
            .onFailure { AppErrorManager.reportError("Background sync scheduling failed", it) }
        if (BuildConfig.DEBUG) verifyLedgerInBackground()
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
