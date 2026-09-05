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
        "com.android.settings"
    )

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
        _excluded.value = prefs.getStringSet(KEY_EXCLUDED, null)?.toSet() ?: DEFAULT_EXCLUDED
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
