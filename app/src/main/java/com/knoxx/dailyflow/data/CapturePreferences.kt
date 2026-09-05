package com.knoxx.dailyflow.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What DailyFlow is allowed to read, and whether it is reading at all.
 *
 * Backs the "Manage app access" screen and the Pause control on You.
 */
object CapturePreferences {

    private const val PREFS = "dailyflow_capture"
    private const val KEY_PAUSED = "paused"
    private const val KEY_EXCLUDED = "excluded_packages"
    private const val KEY_DEFAULTS_VERSION = "defaults_version"

    /**
     * Blocked out of the box rather than opt-out.
     *
     * Wick's day-long audit found these categories genuinely exposing
     * sensitive content: a payments app's PIN keypad was fully readable, and
     * one BNPL app detected the accessibility service and force-closed itself.
     * A memory app has no business reading a banking screen, so the safe
     * default is off and the user can opt in — the reverse would mean the
     * first run captures a payment screen before anyone has made a choice.
     */
    private val DEFAULT_EXCLUDED = setOf(
        "com.google.android.apps.nbu.paisa.user", // Google Pay
        "com.phonepe.app",
        "net.one97.paytm",
        "com.dreamplug.androidapp",               // CRED
        "com.snapmint.customerapp",
        "com.android.systemui",                   // status bar, shade, lock screen
        "com.android.settings",

        // Surfaces with no content of their own. Measured on the demo device:
        // these produced 67 of 132 captures — just over half of everything
        // recorded — and not one was worth remembering. Each still cost a
        // three-second inference, so the extraction pass spent most of its
        // time reading a home screen.
        "com.android.launcher3",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.miui.home",
        "com.sec.android.app.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.vivo.remotecontrol",
        "com.vivo.pcsuite",
        "com.vivo.appstore",

        // Gesture and system-UI overlays. These sit above whatever app is
        // actually open, so they fire events naming themselves while the real
        // content on screen belongs to something else — 94 events from upslide
        // and 27 from systemuiplugin in 75 seconds of ordinary use, every one
        // of them later thrown out by the window-mismatch guard. Blocking them
        // here drops them a stage earlier and saves reading the window at all.
        "com.vivo.upslide",
        "com.vivo.systemuiplugin",
        "com.vivo.globalsearch",
        "com.vivo.aiengine",

        // Storefront and keyboard. A keyboard is the worst of these: it echoes
        // what is being typed into whatever app is open, so capturing it means
        // recording the same words twice, once without any idea where they
        // were going.
        "com.android.vending",
        "com.google.android.inputmethod.latin"
    )

    /**
     * Bumped whenever [DEFAULT_EXCLUDED] gains entries.
     *
     * Without this, defaults only ever reach someone who has never touched the
     * screen: the moment one app is toggled the whole set is written to disk,
     * and every later addition silently misses everyone who had used the
     * feature. New defaults are merged in once per version — additions only,
     * so an app the user deliberately allowed is never re-blocked behind their
     * back.
     */
    private const val DEFAULTS_VERSION = 3

    private lateinit var prefs: SharedPreferences

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _excluded = MutableStateFlow<Set<String>>(emptySet())
    val excluded: StateFlow<Set<String>> = _excluded.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _isPaused.value = prefs.getBoolean(KEY_PAUSED, false)

        // getStringSet returns the live set, which must not be mutated — copy it.
        val stored = prefs.getStringSet(KEY_EXCLUDED, null)?.toSet()
        val seenVersion = prefs.getInt(KEY_DEFAULTS_VERSION, 0)

        _excluded.value = when {
            stored == null -> DEFAULT_EXCLUDED
            // Union, never intersection: an app the user explicitly allowed
            // stays allowed unless it is genuinely new to the default list.
            seenVersion < DEFAULTS_VERSION -> stored + DEFAULT_EXCLUDED
            else -> stored
        }

        prefs.edit()
            .putStringSet(KEY_EXCLUDED, _excluded.value)
            .putInt(KEY_DEFAULTS_VERSION, DEFAULTS_VERSION)
            .apply()
    }

    fun setPaused(paused: Boolean) {
        _isPaused.value = paused
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun setAllowed(packageName: String, allowed: Boolean) {
        val updated = if (allowed) _excluded.value - packageName else _excluded.value + packageName
        _excluded.value = updated
        prefs.edit().putStringSet(KEY_EXCLUDED, updated).apply()
    }

    fun isAllowed(packageName: String): Boolean = packageName !in _excluded.value
}
