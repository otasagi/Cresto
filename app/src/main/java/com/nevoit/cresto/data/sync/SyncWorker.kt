package com.nevoit.cresto.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for background WebDAV sync.
 * Used for periodic fallback sync and network-recovery triggered sync.
 */
class SyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val syncManager = SyncManagerAccessor.syncManager ?: return Result.failure()

        return when (val result = syncManager.requestSyncAndGetResult()) {
            is SyncResult.Success -> Result.success()
            is SyncResult.SkippedNoChanges -> Result.success()
            is SyncResult.Conflict -> Result.success()
            is SyncResult.Error -> {
                if (result.isRetryable) Result.retry() else Result.failure()
            }
            is SyncResult.NotConfigured -> Result.success()
            is SyncResult.AlreadyRunning -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_PERIODIC_NAME = "cresto_webdav_periodic_sync"
        private const val UNIQUE_NETWORK_NAME = "cresto_webdav_network_sync"
        private const val PERIODIC_INTERVAL_HOURS = 2L

        /**
         * Register the periodic background sync and network-recovery trigger.
         * Safe to call multiple times (KEEP policy).
         */
        fun register(context: Context) {
            val workManager = WorkManager.getInstance(context)

            // Periodic sync as fallback (every 2 hours)
            val periodicRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            workManager.enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest
            )

            // Network recovery trigger
            val networkRequest = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            workManager.enqueueUniqueWork(
                UNIQUE_NETWORK_NAME,
                ExistingWorkPolicy.REPLACE,
                networkRequest
            )
        }
    }
}

/**
 * Global accessor for SyncManager from WorkManager workers.
 * Set during Koin initialization in CrestoApplication.
 */
object SyncManagerAccessor {
    @Volatile
    var syncManager: SyncManager? = null
}
