package com.knoxx.dailyflow.capture

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityEvent
import com.knoxx.dailyflow.data.Capture
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.overlay.BubblePreferences
import com.knoxx.dailyflow.overlay.ReplyBubble

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

    /**
     * QOO over the top of other apps.
     *
     * Lives here rather than in a service of its own because an accessibility
     * service may add a TYPE_ACCESSIBILITY_OVERLAY window with no further
     * permission — no "display over other apps" prompt — and because this
     * class already has the screen text the bubble needs. Created lazily so a
     * user who never turns the bubble on never builds one.
     */
    private var bubble: ReplyBubble? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        MemoryRepository.init(applicationContext)
        CapturePreferences.init(applicationContext)
        BubblePreferences.init(applicationContext)
        CaptureStats.init(applicationContext)
        CaptureStats.markCaptureStarted()
    }

    override fun onDestroy() {
        bubble?.destroy()
        bubble = null
        CaptureStats.flush()
        CaptureStats.markCaptureStopped()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType !in watched) return

        val packageName = event.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        val paused = CapturePreferences.isPaused.value
        val locked = keyguard?.isKeyguardLocked == true
        val allowed = CapturePreferences.isAllowed(packageName)

        // The bubble is decided here, before the capture gates, and against only the
        // two conditions that actually concern it: the user's own off switch, and the
        // lock screen. Not the allow-list — that governs what may be *read*, and the
        // bubble reads nothing until tapped.
        //
        // It used to be decided near the end of this method, behind every capture
        // filter — including `extracted.text.isBlank()`. So a Gmail screen that
        // produced no text, which a Compose view or a WebView routinely does, took QOO
        // off screen with it, at exactly the moment he is meant to be there. Worse,
        // every one of those filters returns, so the matching hide() never ran either
        // and he could equally be left stranded over whatever came next.
        //
        // Whether a screen is worth *reading* and whether QOO should be *visible* are
        // different questions. Answering the second with the first is what made him
        // disappear.
        if (paused || locked) {
            bubble?.hide()
        } else {
            updateBubble(packageName)
        }

        // The user's own pause switch wins over everything.
        if (paused) return

        // The lock screen is not a foreground app. Its clock ticks every
        // minute, and its notification previews surfaced genuinely sensitive
        // content in Wick's audit — a full delivery address and an OTP-adjacent
        // message, readable while the phone was locked. Skip entirely.
        if (locked) {
            DebugLog.log("capture", "drop locked")
            return
        }

        if (!allowed) {
            DebugLog.log("capture", "drop not-allowed $packageName")
            return
        }

        val root = rootInActiveWindow ?: return

        // event.packageName and rootInActiveWindow come from two separate
        // asynchronous system calls. On a fast app switch the active window can
        // change between them, filing one app's content under another app's
        // name — confirmed in Wick's audit, where every event logged under a
        // banking app actually contained content from a social app. Trust only
        // the events where the two agree.
        if (root.packageName?.toString() != packageName) {
            DebugLog.log("capture", "drop window-mismatch event=$packageName root=${root.packageName}")
            return
        }

        val extracted = NodeExtractor.extract(root)
        if (extracted.text.isBlank()) {
            DebugLog.log("capture", "drop empty-text $packageName")
            return
        }

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

    /**
     * Shows or hides QOO for the app that is in front.
     *
     * Only the package is passed. The bubble used to be handed this pass's
     * extracted text as well, which made the draft answer whichever screen
     * last fired an event rather than the one under the user's thumb — it
     * replied to an inbox list while an invitation was open. It now reads the
     * window itself when tapped.
     *
     * Hidden rather than left in place on a non-repliable screen, because a
     * bubble that lingers over a banking app is exactly the thing the
     * allow-list exists to prevent.
     */
    private fun updateBubble(packageName: String) {
        if (!BubblePreferences.enabled.value) {
            bubble?.hide()
            return
        }

        // Shown on every app, not only the ones in RepliableApps.
        //
        // The narrow list made QOO a Gmail feature that happened to be drawn on top of
        // things. Always-on makes him the assistant you reach for wherever you are,
        // which is the point of a mascot that is also a control. He still reads the
        // window only when tapped, so being visible costs nothing until used.
        //
        // The trade, stated plainly: he will now also appear over apps outside the
        // allow-list, including ones the allow-list exists to keep DailyFlow out of.
        // He reads nothing there unless tapped, and the switch in Profile turns him off
        // entirely — but a banking app with QOO floating on it is a sight worth having
        // decided on deliberately rather than discovering.
        val current = bubble ?: ReplyBubble(this).also { bubble = it }
        current.onScreen(packageName)
    }

    override fun onInterrupt() {
        // Nothing held between events, so nothing to tear down.
    }
}
