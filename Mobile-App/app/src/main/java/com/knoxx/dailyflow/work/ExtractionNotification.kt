package com.knoxx.dailyflow.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.knoxx.dailyflow.R

/**
 * The notification that makes background extraction survivable.
 *
 * This is not decoration. Loading Gemma costs several gigabytes across four
 * OpenCL subgraphs, and from an ordinary background job the process sits at
 * killable priority — that load previously triggered a system-wide low-memory
 * event and the platform killed a dozen apps before killing this one, which
 * WorkManager then restarted into the same wall.
 *
 * Promoting the work to a foreground service moves the process into a
 * protected bucket, so the system evicts other things to make room instead of
 * evicting us. The notification is the price Android charges for that
 * protection, and it is a fair one: the app is reading the user's screen
 * content, and a visible indicator while it does is the honest arrangement.
 */
object ExtractionNotification {

    private const val CHANNEL_ID = "dailyflow_reading"
    const val NOTIFICATION_ID = 4101

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Reading your day",
            // Low: it must be visible, but it is a status indicator rather
            // than something worth a sound or a heads-up interruption.
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while DailyFlow is understanding captured screens on this device."
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun foregroundInfo(context: Context, done: Int, total: Int): ForegroundInfo {
        ensureChannel(context)

        val text = when {
            total <= 0 -> "Starting…"
            done == 0 -> "Loading the model…"
            else -> "$done of $total"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Reading your day")
            .setContentText("$text · on this device")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .build()

        // dataSync is the correct subtype: this is processing the user's own
        // already-captured data, not media, location or a call.
        return ForegroundInfo(
            NOTIFICATION_ID,
            notification,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
