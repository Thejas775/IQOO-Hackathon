package com.knoxx.dailyflow.capture

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * Whether the user has granted the accessibility permission.
 *
 * There is no API to request it — it can only be turned on by hand in system
 * Settings, which is why the You screen opens Settings rather than showing a
 * permission dialog.
 */
object CapturePermission {

    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, CaptureService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        // Colon-separated list of flattened component names. Parsed rather
        // than substring-matched, so an app with a similarly-prefixed package
        // cannot produce a false positive.
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
        }
        return false
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
