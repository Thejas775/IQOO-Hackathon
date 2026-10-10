package com.knoxx.dailyflow.sync

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
 * Runs a PC sync in the background. Always reports success: an unreachable
 * PC is the normal state away from home, and a failure would make WorkManager
 * retry with backoff all day for a computer that is simply switched off.
 */
class PcSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (PairedComputers.all(applicationContext).isEmpty()) return Result.success()
        PcSync.syncAll(applicationContext)
        return Result.success()
    }
}

object PcSyncScheduler {

    private const val PERIODIC = "dailyflow_pc_sync"
    private const val SOON = "dailyflow_pc_sync_soon"

    /** Unmetered approximates "on Wi-Fi", which is the only place the PC can
     *  be reached; on mobile data a sync would only time out. */
    private val constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .build()

    /** Every 15 minutes, as extraction. KEEP so a restart does not reset it. */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PcSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    /**
     * Shortly after a change here, so a task ticked on the phone shows as done
     * on the PC within seconds rather than at the next period.
     *
     * KEEP rather than REPLACE: REPLACE would cancel a sync already in
     * flight, mid-socket, every time an extraction pass saved another row.
     * A pending sync reads the changes when it runs, so it picks these up.
     */
    fun requestSoon(context: Context, delaySeconds: Long = 5) {
        if (PairedComputers.all(context).isEmpty()) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            SOON,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PcSyncWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(constraints)
                .build()
        )
    }
}
