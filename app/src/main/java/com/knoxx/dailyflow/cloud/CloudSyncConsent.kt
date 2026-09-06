package com.knoxx.dailyflow.cloud

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the owner has explicitly agreed to sync memories to Supabase.
 * Defaults to false — CloudSyncWorker checks this before every run and
 * no-ops (leaving everything pending) until ConnectClaudeScreen's consent
 * dialog sets it true. Unlike CapturePreferences this has no separate
 * init(context) step: it's read from very few call sites (the worker,
 * that one screen), so a plain per-call SharedPreferences read is simpler
 * than requiring app-wide initialization order.
 */
object CloudSyncConsent {

    private const val PREFS_NAME = "wick_cloud_sync_prefs"
    private const val KEY_CONSENTED = "has_consented"

    private val _hasConsented = MutableStateFlow(false)
    val hasConsentedFlow: StateFlow<Boolean> = _hasConsented.asStateFlow()

    fun hasConsented(context: Context): Boolean {
        val consented = prefs(context).getBoolean(KEY_CONSENTED, false)
        _hasConsented.value = consented
        return consented
    }

    fun setConsented(context: Context, consented: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONSENTED, consented).apply()
        _hasConsented.value = consented
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
