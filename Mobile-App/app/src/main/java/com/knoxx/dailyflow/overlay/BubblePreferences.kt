package com.knoxx.dailyflow.overlay

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether QOO is allowed to appear over other apps.
 *
 * Off by default, and the default matters: a mascot that turns up uninvited
 * on top of someone's inbox the first time they open it is the behaviour
 * people uninstall an app for. It has to be asked for.
 *
 * Read from the accessibility service, which may be running before any
 * activity exists, so this keeps its own SharedPreferences rather than
 * depending on a screen having initialised it.
 */
object BubblePreferences {

    private const val PREFS = "dailyflow_bubble"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun init(context: Context) {
        _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
