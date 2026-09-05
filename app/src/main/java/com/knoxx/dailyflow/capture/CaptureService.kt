package com.knoxx.dailyflow.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityEvent
import com.knoxx.dailyflow.data.Capture
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository

/**
 * Watches on-screen text across other apps and hands each screen to
 * [MemoryRepository].
 *
 * Every guard below exists because of something found in Wick's day-long
 * capture audit, not from caution in the abstract. They run in cost order:
 * the cheap boolean checks come before fetching and walking the node tree.
 *
 * There is no file I/O here at all. Callbacks arrive on the main thread, and
 * anything blocking in this method stalls the UI of whatever app the user is
 * actually using — which is why Wick's per-event JSONL write was a real
 * problem and why the equivalent does not exist in DailyFlow.
 */
class CaptureService : AccessibilityService() {

    private val watched = setOf(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_FOCUSED
    )

    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        MemoryRepository.init(applicationContext)
        CapturePreferences.init(applicationContext)
        CaptureStats.init(applicationContext)
        CaptureStats.markCaptureStarted()
    }

    override fun onDestroy() {
        CaptureStats.flush()
        CaptureStats.markCaptureStopped()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType !in watched) return

        // The user's own pause switch wins over everything.
        if (CapturePreferences.isPaused.value) return

        // The lock screen is not a foreground app. Its clock ticks every
        // minute, and its notification previews surfaced genuinely sensitive
        // content in Wick's audit — a full delivery address and an OTP-adjacent
        // message, readable while the phone was locked. Skip entirely.
        if (keyguard?.isKeyguardLocked == true) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return
        if (!CapturePreferences.isAllowed(packageName)) return

        val root = rootInActiveWindow ?: return

        // event.packageName and rootInActiveWindow come from two separate
        // asynchronous system calls. On a fast app switch the active window can
        // change between them, filing one app's content under another app's
        // name — confirmed in Wick's audit, where every event logged under a
        // banking app actually contained content from a social app. Trust only
        // the events where the two agree.
        if (root.packageName?.toString() != packageName) return

        val extracted = NodeExtractor.extract(root)
        if (extracted.text.isBlank()) return

        CaptureStats.recordEvent()
        MemoryRepository.enqueue(
            Capture(
                timestamp = System.currentTimeMillis(),
                packageName = packageName,
                eventType = AccessibilityEvent.eventTypeToString(event.eventType),
                text = extracted.text
            )
        )
    }

    override fun onInterrupt() {
        // Nothing held between events, so nothing to tear down.
    }
}
