package com.shopledger.pos.core.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Disabled until sub-project 2 (Cloud & accounts) rebuilds sync on top of `sync_outbox`.
 * The v2 implementation pushed absolute stock and balances, which loses updates across devices.
 */
class FirestoreDeltaSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = Result.success()
}
