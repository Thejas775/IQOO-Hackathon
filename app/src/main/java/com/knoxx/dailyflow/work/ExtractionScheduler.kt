package com.knoxx.dailyflow.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Decides when extraction runs.
 *
 * Two mechanisms, and the split matters. A **one-time** request is kicked
 * whenever a capture lands, which is what makes memories appear within
 * seconds; WorkManager's 15-minute floor applies only to periodic work, so
 * Wick's quarter-hour lag was never a platform limit, just a consequence of
 * batching for the network. A **periodic** run then acts purely as a safety
 * net for kicks lost to a process death or a Doze window.
 */
object ExtractionScheduler {

    private const val IMMEDIATE_WORK = "dailyflow_extraction_now"
    private const val SAFETY_NET_WORK = "dailyflow_extraction_safety_net"

    /**
     * Battery-not-low is the only constraint. Notably absent: any network
     * constraint. Wick required NetworkType.CONNECTED because extraction was
     * an API call; requiring connectivity here would mean the offline app
     * stops understanding things whenever it goes offline, which is precisely
     * backwards.
     */
    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    /**
     * Called after each capture is stored.
     *
     * KEEP, not REPLACE: the worker drains the entire queue, so a run already
     * in flight will pick up this row too. REPLACE would cancel that run
     * mid-inference and start over, which at ~3 seconds per row means a busy
     * period could livelock, forever restarting and never finishing.
     */
    fun kick(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExtractionWorker>()
                .setConstraints(constraints)
                .build()
        )
    }

    /** Started once from Application. */
    fun scheduleSafetyNet(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SAFETY_NET_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExtractionWorker>(30, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(IMMEDIATE_WORK)
            cancelUniqueWork(SAFETY_NET_WORK)
        }
    }
}
