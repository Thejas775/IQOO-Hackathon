package com.knoxx.dailyflow.ui

import androidx.compose.ui.graphics.Color
import com.knoxx.dailyflow.ui.theme.BlueInk
import com.knoxx.dailyflow.ui.theme.BlueTint
import com.knoxx.dailyflow.ui.theme.GreenInk
import com.knoxx.dailyflow.ui.theme.GreenTint
import com.knoxx.dailyflow.ui.theme.InkMuted
import com.knoxx.dailyflow.ui.theme.OrangeInk
import com.knoxx.dailyflow.ui.theme.OrangeTint
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.VioletDeep
import com.knoxx.dailyflow.ui.theme.VioletTint

/**
 * Turns a captured package name into the badge shown on timeline rows and
 * commitment cards — "SLACK", "WHATSAPP", "GMAIL".
 *
 * Deliberately a small curated list plus a readable fallback, not an attempt
 * to name every app on the device. Querying PackageManager for a real label
 * would be more accurate but needs QUERY_ALL_PACKAGES, which DailyFlow does
 * not request; the fallback derives something legible from the package name
 * instead, so an unlisted app degrades to "ZEPTO" rather than a blank chip.
 */
data class SourceApp(val label: String, val tint: Color, val ink: Color)

// Rebuilt per lookup rather than held, for the same reason as the category
// colours: a held map would freeze its tints at class-init and survive a
// theme switch. Thirteen entries is cheap next to the row it decorates.
private val KNOWN get() = mapOf(
    "com.whatsapp" to SourceApp("WhatsApp", GreenTint, GreenInk),
    "com.whatsapp.w4b" to SourceApp("WhatsApp", GreenTint, GreenInk),
    "com.Slack" to SourceApp("Slack", VioletTint, VioletDeep),
    "com.google.android.gm" to SourceApp("Gmail", OrangeTint, OrangeInk),
    "com.instagram.android" to SourceApp("Instagram", OrangeTint, OrangeInk),
    "org.telegram.messenger" to SourceApp("Telegram", BlueTint, BlueInk),
    "com.microsoft.teams" to SourceApp("Teams", VioletTint, VioletDeep),
    "com.linkedin.android" to SourceApp("LinkedIn", BlueTint, BlueInk),
    "com.google.android.apps.messaging" to SourceApp("Messages", BlueTint, BlueInk),
    "com.google.android.calendar" to SourceApp("Calendar", BlueTint, BlueInk),
    "com.google.android.apps.docs" to SourceApp("Docs", BlueTint, BlueInk),
    "com.discord" to SourceApp("Discord", VioletTint, VioletDeep),
    "com.notion.id" to SourceApp("Notion", PaperSunken, InkMuted)
)

/** Rows the user typed themselves, rather than anything captured. */
val ManualSource get() = SourceApp("Manual", PaperSunken, InkMuted)

/** Events read from the device calendar provider, not from screen capture. */
val CalendarSource get() = SourceApp("Calendar", BlueTint, BlueInk)

fun sourceAppFor(packageName: String?): SourceApp {
    if (packageName.isNullOrBlank()) return ManualSource
    KNOWN[packageName]?.let { return it }

    // Fallback: take the most distinctive path segment. "com.zeptoconsumerapp"
    // → "Zeptoconsumerapp" is ugly, so prefer the last segment and cap it.
    val segment = packageName.substringAfterLast('.')
        .takeIf { it.isNotBlank() && !it.equals("android", ignoreCase = true) }
        ?: packageName.split('.').getOrNull(1)
        ?: packageName

    return SourceApp(
        label = segment.replaceFirstChar { it.uppercase() }.take(12),
        tint = PaperSunken,
        ink = InkMuted
    )
}
