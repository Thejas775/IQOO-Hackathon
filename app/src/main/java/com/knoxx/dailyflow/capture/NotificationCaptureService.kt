package com.knoxx.dailyflow.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.knoxx.dailyflow.data.Capture
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository

/**
 * Captures notifications as a second source alongside on-screen text.
 *
 * This closes a real gap. The accessibility service only sees an app while it
 * is open, so a message that arrives and is never tapped — the exact case
 * where a commitment is most likely to be forgotten — was invisible to
 * DailyFlow. Notifications catch those.
 *
 * Separate permission, granted separately. Notification access is its own
 * grant in system Settings, so enabling capture does not silently enable this
 * too; the user turns each on deliberately.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        MemoryRepository.init(applicationContext)
        CapturePreferences.init(applicationContext)
        CaptureStats.init(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val packageName = sbn.packageName ?: return

        if (CapturePreferences.isPaused.value) return
        if (packageName == applicationContext.packageName) return
        if (!CapturePreferences.isAllowed(packageName)) return

        // Ongoing notifications are media players, downloads, navigation and
        // foreground-service badges. They update constantly, say nothing worth
        // remembering, and would flood the queue.
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()

        // BIG_TEXT carries the full message where TEXT is truncated with an
        // ellipsis, so it is preferred — a summary built from half a sentence
        // is worse than none.
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim()

        val text = listOfNotNull(title, body)
            .filter { it.isNotEmpty() }
            .joinToString(": ")
        if (text.length < MIN_USEFUL_LENGTH) return

        CaptureStats.recordEvent()
        MemoryRepository.enqueue(
            Capture(
                timestamp = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis(),
                packageName = packageName,
                // Marked distinctly so the debounce meant for keystroke bursts
                // never applies here — a notification is a single discrete
                // event, not a stream of edits.
                eventType = "NOTIFICATION",
                text = text
            )
        )
    }

    private companion object {
        /** Below this it is a badge or a count, not a message. */
        const val MIN_USEFUL_LENGTH = 12
    }
}

/** Whether the user has granted notification access, and how to ask. */
object NotificationPermission {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, NotificationCaptureService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        // Parsed as components rather than substring-matched, so a similarly
        // named package cannot produce a false positive.
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == expected }
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
