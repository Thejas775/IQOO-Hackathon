package com.knoxx.dailyflow.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Two identities, never a flip of one another.
 *
 * Paper is the shipping look. QOO is the mascot's own colours — and it is a
 * separate palette rather than a dark-mode inversion for exactly the reason
 * dark mode was refused here before: inverting the tints goes muddy and puts
 * a saturated gradient on black, which is the generic "AI app" look this
 * palette was built to avoid. Every tone in the QOO set was picked against a
 * dark ground, not computed from the light one. See `Palette.kt`.
 *
 * A getter, not a val — the scheme has to be rebuilt when the palette changes,
 * and reading it inside a composable is what makes the switch take effect
 * without recreating the activity.
 *
 * Dynamic colour stays off in both: DailyFlow's identity *is* its palette, and
 * letting the launcher wallpaper recolour it would erase that.
 */
private val DailyFlowColors get() = lightColorScheme(
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
    // Read here, in composition, so this composable subscribes to the palette
    // and both the bars and the scheme below refresh on a switch.
    val palette = current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // A light ground needs dark status-bar icons and a dark ground
            // needs light ones; get this backwards and the clock vanishes
            // into the bar. Keyed on the palette so it follows the switch.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !palette.lightStatusBarIcons
                isAppearanceLightNavigationBars = !palette.lightStatusBarIcons
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
