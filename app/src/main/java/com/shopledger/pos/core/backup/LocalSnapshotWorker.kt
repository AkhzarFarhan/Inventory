package com.shopledger.pos.core.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shopledger.pos.data.local.DatabaseHealth
import com.shopledger.pos.data.local.LpsDatabase
import com.shopledger.pos.data.local.PendingRestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class LocalSnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (DatabaseHealth.state.value !is DatabaseHealth.State.Ok) return@withContext Result.success()
        // A swapped-in restore is not confirmed yet: the file on disk is neither safe to snapshot nor settled.
        if (PendingRestore.isPending(applicationContext, LpsDatabase.DB_NAME)) return@withContext Result.success()
        runCatching { BackupManager.forApp(applicationContext).createEncryptedSnapshot() }
            .fold(
                onSuccess = { Result.success() },
                onFailure = { if (runAttemptCount < 3) Result.retry() else Result.failure() }
            )
    }

    companion object {
        private const val WORK_NAME = "shopledger_local_snapshot"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocalSnapshotWorker>(24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiresDeviceIdle(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
