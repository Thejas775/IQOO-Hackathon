package com.knoxx.dailyflow.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light only, on purpose.
 *
 * The redesign commits to a warm paper ground — the ink tones, the tint
 * backgrounds behind category tags, and the violet gradient on the morning
 * brief are all chosen against a light surface. A naive dark inversion would
 * turn the tints muddy and put a saturated violet gradient on black, which is
 * the generic "AI app" look the palette was built to avoid. Dark mode is a
 * real design exercise for later, not a colour flip.
 *
 * Dynamic colour is also off: DailyFlow's identity *is* this palette, and
 * letting the launcher wallpaper recolour it would erase that.
 */
private val DailyFlowColors = lightColorScheme(
    primary = Violet,
    onPrimary = CardWhite,
    primaryContainer = VioletTint,
    onPrimaryContainer = VioletDeep,

    secondary = Green,
    onSecondary = CardWhite,
    secondaryContainer = GreenTint,
    onSecondaryContainer = GreenInk,

    tertiary = Orange,
    onTertiary = CardWhite,
    tertiaryContainer = OrangeTint,
    onTertiaryContainer = OrangeInk,

    background = Paper,
    onBackground = Ink,

    // `surface` is the card white; `surfaceVariant` is the sunken panel.
    // Screens paint their own ground with `background`, so a Surface left at
    // the default reads as a card, which is the common case.
    surface = CardWhite,
    onSurface = Ink,
    surfaceVariant = PaperSunken,
    onSurfaceVariant = InkSecondary,

    outline = BorderStrong,
    outlineVariant = BorderSoft,

    error = Danger,
    onError = CardWhite
)

@Composable
fun DailyFlowTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Light ground means dark status-bar icons; without this the
            // system draws white-on-white and the clock disappears.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = DailyFlowColors,
        typography = DailyFlowTypography,
        shapes = DailyFlowShapes,
        content = content
    )
}
