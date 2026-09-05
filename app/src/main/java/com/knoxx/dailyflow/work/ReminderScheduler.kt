package com.knoxx.dailyflow.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.knoxx.dailyflow.MainActivity
import com.knoxx.dailyflow.R
import com.knoxx.dailyflow.debug.DebugLog
import java.util.concurrent.TimeUnit

/**
 * The one nudge a commitment gets, at the time it is actually due.
 *
 * Separate channel from the extraction service's own notification: that one is
 * a silent, ongoing "reading your day" badge the user should be able to mute
 * without also muting the reminders, which are the point of the app.
 */
object ReminderScheduler {

    const val CHANNEL_ID = "dailyflow_reminders"
    private const val WORK_PREFIX = "dailyflow_reminder_"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "One nudge per commitment, when it is due"
            }
        )
    }

    /**
     * Keyed on the memory id with REPLACE, so a row re-extracted by a retry or
     * a manual sync re-schedules its reminder instead of stacking a second one.
     *
     * A due time already in the past fires immediately rather than being
     * dropped: a commitment surfaced late is still worth surfacing, and
     * silently discarding it would look like the reminder failed.
     */
    fun schedule(context: Context, memoryId: Long, dueAtMillis: Long) {
        val delay = (dueAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_MEMORY_ID to memoryId))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_PREFIX$memoryId",
            ExistingWorkPolicy.REPLACE,
            request
        )
        DebugLog.log("remind", "scheduled id=$memoryId in ${delay / 1000}s")
    }

    /** A memory that stopped being a commitment — edited, marked done, erased
     *  — must not still ring later. */
    fun cancel(context: Context, memoryId: Long) {
        WorkManager.getInstance(context).cancelUniqueWork("$WORK_PREFIX$memoryId")
        DebugLog.log("remind", "cancelled id=$memoryId")
    }

    fun show(context: Context, summary: String, packageName: String, notificationId: Int) {
        ensureChannel(context)
        // Silently skipped without the runtime permission. There is nothing
        // useful to do here instead, and throwing would kill the worker.
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugLog.log("remind", "suppressed id=$notificationId (no POST_NOTIFICATIONS)")
            return
        }

        val open = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("DailyFlow")
            .setContentText(summary)
            // The summary is a full sentence and routinely longer than one
            // collapsed line, so it is given a style that can show all of it.
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId, notification)
        DebugLog.log("remind", "fired id=$notificationId :: $summary")
    }
}
