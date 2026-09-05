package com.knoxx.dailyflow.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object ExtractionScheduler {

    private const val PERIODIC_WORK = "dailyflow_extraction_periodic"
    private const val MANUAL_WORK = "dailyflow_extraction_now"

    /**
     * 15 minutes — WorkManager's own floor, and independently the right number
     * here. Loading the model costs around 30 seconds, so a shorter period
     * would spend most of its time loading rather than working.
     */
    private const val PERIOD_MINUTES = 15L

    /**
     * No network constraint, deliberately: extraction is entirely on-device,
     * and requiring connectivity would mean the offline app stops
     * understanding things the moment it goes offline.
     *
     * Battery-not-low is kept. A 30-second GPU load on a nearly flat phone is
     * exactly the work a user would want deferred.
     */
    private val constraints = Constraints.Builder()
        .setRequiresBatteryNotLow(true)
        .build()

    /** Started once from Application. KEEP so an existing schedule survives
     *  app restarts rather than resetting its interval every launch. */
    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ExtractionWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    /**
     * The "Read my day" button.
     *
     * KEEP rather than REPLACE: the worker drains the whole queue, so a run
     * already in flight will pick up everything anyway, and REPLACE would
     * cancel it mid-inference and reload the model from scratch.
     *
     * No battery constraint here — the user asked for it explicitly, and
     * silently doing nothing because the battery is low would read as the
     * button being broken.
     */
    fun runNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExtractionWorker>().build()
        )
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(PERIODIC_WORK)
            cancelUniqueWork(MANUAL_WORK)
        }
    }
}
