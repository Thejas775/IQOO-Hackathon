package com.knoxx.dailyflow.overlay

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.knoxx.dailyflow.R
import com.knoxx.dailyflow.capture.NodeExtractor
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.BriefGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * QOO, floating over whatever app you are reading, offering to write the reply.
 *
 * Owned by the accessibility service rather than a service of its own, and
 * that is the whole trick. A TYPE_ACCESSIBILITY_OVERLAY window needs no
 * "display over other apps" permission — the one an accessibility service may
 * add is exempt — so the feature costs the user no second scary grant, and it
 * inherits a service that is already running and already holding the screen
 * text it needs. A separate overlay service would have had to ask for
 * SYSTEM_ALERT_WINDOW and then re-read the screen it was handed for free.
 *
 * The window is deliberately NOT focusable. If it took input focus the reply
 * box behind it would lose it, and the focused field is exactly what the
 * finished draft gets typed into — the bubble would break the thing it exists
 * to do.
 */
class ReplyBubble(private val service: AccessibilityService) {

    private val windows = service.getSystemService(WindowManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var bubble: View? = null
    private var panel: View? = null
    private var draftJob: Job? = null

    /**
     * Only the package is kept between events. The TEXT is deliberately not.
     *
     * It was, and that was the bug: the cached text was whatever the last
     * accessibility event happened to carry, so opening an email and tapping
     * QOO drafted a reply to the inbox list behind it — observed answering a
     * portfolio statement while a meeting invitation was on screen. The screen
     * is now read at the moment of the tap, which is the only moment the
     * answer is actually known to be right.
     */
    private var screenPackage: String = ""

    // ── lifecycle ────────────────────────────────────────────────────────

    /**
     * Called on every qualifying screen. Cheap: it only touches the window
     * manager when the bubble's visibility actually changes, because this runs
     * inside the accessibility callback and anything slow here stalls the UI
     * of the app the user is using.
     */
    fun onScreen(packageName: String) {
        // A new screen invalidates a draft written for the old one. Left open,
        // the panel would sit over a different email still showing the
        // previous reply, which is worse than showing nothing.
        if (packageName != screenPackage) removePanel()
        screenPackage = packageName
        if (bubble == null) showBubble()
    }

    fun hide() {
        draftJob?.cancel()
        removePanel()
        bubble?.let { runCatching { windows.removeView(it) } }
        bubble = null
    }

    fun destroy() {
        hide()
        scope.coroutineContext[Job]?.cancel()
    }

    // ── collapsed ────────────────────────────────────────────────────────

    private fun showBubble() {
        val size = dp(56)
        val view = ImageView(service).apply {
            setImageResource(R.drawable.qoo_waving)
            background = circle(BUBBLE_GROUND)
            // The art is drawn on its own black ground, so without clipping it
            // reads as a black square pasted onto whatever app is behind —
            // most visible over a white inbox, which is exactly where this
            // feature is meant to be used.
            clipToOutline = true
            scaleType = ImageView.ScaleType.CENTER_CROP
            elevation = dp(6).toFloat()
        }

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = service.resources.displayMetrics.widthPixels - size - dp(12)
            y = service.resources.displayMetrics.heightPixels / 2
        }

        view.setOnTouchListener(DragOrTap(params) { onBubbleTapped() })

        runCatching { windows.addView(view, params) }
            .onFailure { DebugLog.log("bubble", "add failed: ${it.message}") }
            .onSuccess { bubble = view }
    }

    /**
     * Drag to move, tap to open — told apart by distance, not by timing.
     *
     * A long press that never moves is still a tap here, because the bubble
     * sits over someone else's app and a press that does nothing visible reads
     * as the bubble being broken.
     */
    private inner class DragOrTap(
        private val params: WindowManager.LayoutParams,
        private val onTap: () -> Unit
    ) : View.OnTouchListener {

        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    runCatching { windows.updateViewLayout(view, params) }
                }

                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }

    // ── drafting ─────────────────────────────────────────────────────────

    private fun onBubbleTapped() {
        if (draftJob?.isActive == true) return
        showPanel(PanelState.Thinking)

        draftJob = scope.launch {
            val pkg = screenPackage
            // Read here, on the main thread, before anything suspends: the
            // active window is only reliably readable while the tap that
            // opened this is still the most recent thing to have happened.
            val text = readScreenNow()

            val draft = withContext(Dispatchers.Default) { draftFor(pkg, text) }

            showPanel(
                if (draft.isNullOrBlank()) PanelState.Failed else PanelState.Ready(draft)
            )
        }
    }

    /**
     * The words on screen right now, straight from the active window.
     *
     * Costs one tree walk per tap, which is affordable precisely because it is
     * per tap — the capture pipeline's reason for not re-reading the window
     * (it runs on every accessibility event) does not apply to something the
     * user deliberately pressed.
     */
    private fun readScreenNow(): String {
        val root = service.rootInActiveWindow ?: return ""
        if (root.packageName?.toString() != screenPackage) {
            // Same window-mismatch guard the capture path uses: the two calls
            // are asynchronous and on a fast switch they disagree, which would
            // file one app's words under another app's name.
            DebugLog.log("bubble", "window mismatch, not drafting")
            return ""
        }
        return NodeExtractor.extract(root).text
    }

    /**
     * Answer whatever is on screen.
     *
     * One call into [BriefGenerator.draftReplyToScreen], which owns both the
     * reading and the writing. This used to run the extraction prompt here and
     * then pick a memory to answer — see the note on that function for the two
     * ways that was wrong on the first real screen it met.
     */
    private suspend fun draftFor(packageName: String, text: String): String? {
        if (text.isBlank()) {
            DebugLog.log("bubble", "no text on $packageName")
            return null
        }

        val draft = BriefGenerator.draftReplyToScreen(service, packageName, text)
        DebugLog.log(
            "bubble",
            if (draft == null) "no reply written for $packageName"
            else "replied on $packageName: ${draft.take(60)}"
        )
        return draft
    }

    // ── expanded ─────────────────────────────────────────────────────────

    private sealed interface PanelState {
        data object Thinking : PanelState
        data object Failed : PanelState
        data class Ready(val draft: String) : PanelState
    }

    private fun showPanel(state: PanelState) {
        removePanel()

        val content = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(PANEL_GROUND)
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
            elevation = dp(12).toFloat()
        }

        content.addView(
            TextView(service).apply {
                text = when (state) {
                    PanelState.Thinking -> "QOO is reading this screen…"
                    PanelState.Failed -> "Nothing here to reply to."
                    is PanelState.Ready -> state.draft
                }
                setTextColor(if (state is PanelState.Ready) PANEL_INK else PANEL_INK_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }
        )

        val buttons = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }

        if (state is PanelState.Ready) {
            buttons.addView(
                button("Insert", primary = true) {
                    // Typed in, never sent. The gap between a draft appearing
                    // in the box and the user pressing send is the entire
                    // safety margin of this feature, and an overlay that could
                    // send mail on someone's behalf is a different and far more
                    // dangerous product.
                    val placed = insertIntoFocusedField(state.draft)
                    if (!placed) copyToClipboard(state.draft)
                    toast(
                        if (placed) "Draft placed — check it before sending"
                        else "Copied — no reply box was focused"
                    )
                    removePanel()
                }
            )
            buttons.addView(
                button("Copy") {
                    copyToClipboard(state.draft)
                    toast("Copied")
                    removePanel()
                }
            )
        }
        buttons.addView(button("Close") { removePanel() })
        content.addView(buttons)

        val params = WindowManager.LayoutParams(
            service.resources.displayMetrics.widthPixels - dp(32),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Not focusable, so the reply box behind keeps input focus and is
            // still there to be typed into when Insert is pressed.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }

        runCatching { windows.addView(content, params) }
            .onFailure { DebugLog.log("bubble", "panel failed: ${it.message}") }
            .onSuccess { panel = content }
    }

    private fun removePanel() {
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
    }

    // ── placing the text ─────────────────────────────────────────────────

    /**
     * Types the draft into whatever field the user had focused.
     *
     * ACTION_SET_TEXT rather than paste: paste depends on the clipboard
     * surviving long enough and on the app honouring it, while set-text
     * addresses the node directly. Returns false when no editable field is
     * focused, which is the normal case when the user has not opened the
     * reply box yet — the caller falls back to the clipboard rather than
     * reporting success for text that went nowhere.
     */
    private fun insertIntoFocusedField(text: String): Boolean {
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focused.isEditable) return false

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
        }
        val ok = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        DebugLog.log("bubble", "insert into ${focused.packageName} ok=$ok")
        return ok
    }

    private fun copyToClipboard(text: String) {
        service.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("DailyFlow draft", text))
    }

    // ── chrome ───────────────────────────────────────────────────────────

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) =
        Button(service).apply {
            text = label
            isAllCaps = false
            setTextColor(if (primary) PANEL_GROUND else PANEL_INK)
            background = rounded(if (primary) ACCENT else PANEL_RAISED)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    private fun toast(message: String) =
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(color)
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density).toInt()

    private companion object {
        /** Past this, the finger is moving the bubble rather than pressing it. */
        const val TAP_SLOP = 12f

        // QOO's own colours, hard-coded rather than read from the Compose
        // palette: this draws over other apps, where the host app's light or
        // dark theme is nothing to do with us and a white panel over a dark
        // inbox would be a flashbang.
        val BUBBLE_GROUND = Color.parseColor("#1C1A14")
        val PANEL_GROUND = Color.parseColor("#14130F")
        val PANEL_RAISED = Color.parseColor("#2B2718")
        val PANEL_INK = Color.parseColor("#F7F2E6")
        val PANEL_INK_DIM = Color.parseColor("#B3A994")
        val ACCENT = Color.parseColor("#FFB020")
    }
}

/**
 * Where the bubble is worth offering.
 *
 * A deliberate list rather than "anywhere with a text box". QOO appearing over
 * a banking app or a password field would be alarming, and the feature only
 * earns its place where there is a message addressed to the owner that they
 * are expected to answer.
 */
object RepliableApps {

    private val PACKAGES = setOf(
        "com.google.android.gm",              // Gmail — the one asked for first
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "com.Slack",
        "com.microsoft.teams",
        "com.google.android.apps.messaging",
        "com.linkedin.android",
        "com.discord"
    )

    fun includes(packageName: String): Boolean = packageName in PACKAGES
}
