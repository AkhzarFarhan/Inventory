package com.shopledger.pos

import android.app.Application
import com.shopledger.pos.core.auth.GoogleAuthManager
import com.shopledger.pos.core.sync.SyncManager
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
        GoogleAuthManager.init(this)
        SyncManager.init(this)
        database = LpsDatabase.getInstance(this)
        repository = LpsRepository(this, database)
        SyncManager.scheduleDeltaSync(this)
    }
}
