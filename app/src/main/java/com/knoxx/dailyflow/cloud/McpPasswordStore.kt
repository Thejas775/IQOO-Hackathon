package com.knoxx.dailyflow.cloud

import android.content.Context
import com.knoxx.dailyflow.BuildConfig

/**
 * The password actually shown/used in-app. Defaults to the build-time
 * BuildConfig.MCP_OWNER_PASSWORD (from local.properties), but a successful
 * in-app rotation overwrites it here — BuildConfig is compiled into the
 * APK and can't change at runtime, so without this, rotating the password
 * via ConnectClaudeScreen would leave the app showing/copying a stale value.
 */
object McpPasswordStore {

    private const val PREFS_NAME = "wick_mcp_password_prefs"
    private const val KEY_PASSWORD = "effective_password"

    fun current(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, null) ?: BuildConfig.MCP_OWNER_PASSWORD

    fun set(context: Context, password: String) {
        prefs(context).edit().putString(KEY_PASSWORD, password).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
