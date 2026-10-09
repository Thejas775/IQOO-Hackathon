package com.knoxx.dailyflow.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Runs a cloud sync pass.
 *
 * Wick called this through its own WickWorkScheduler, which also owned
 * extraction and reminders. DailyFlow already has separate schedulers for
 * those and they run on-device, so this is the cloud-only equivalent rather
 * than a port of a scheduler whose other halves would be dead code here.
 *
 * A network constraint, unlike anything else in this app: every other worker
 * is deliberately offline, and this is the one that genuinely cannot proceed
 * without a connection.
 */
object CloudSyncScheduler {

    private const val WORK_NAME = "dailyflow_cloud_sync"

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<CloudSyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        // KEEP rather than REPLACE: the worker takes a mutex and a second
        // enqueue while one is mid-flight would sit waiting on it anyway.
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
