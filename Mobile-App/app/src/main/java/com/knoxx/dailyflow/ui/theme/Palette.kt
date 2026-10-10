package com.knoxx.dailyflow.ui.theme

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * Every colour in the app, as data rather than as constants.
 *
 * The names in Color.kt used to be top-level vals holding literals, which
 * meant the identity was fixed at class-init and trying a second look meant
 * editing 22 files. They are now getters onto whichever palette is [current],
 * so the whole app re-themes from one assignment and every call site is
 * unchanged.
 *
 * The reason this works with no plumbing: [current] is backed by
 * mutableStateOf, so a composable that reads `Paper` registers a snapshot read
 * on it and recomposes when the palette is swapped. The switch is live — no
 * activity recreate, no restart.
 */
data class Palette(
    val paper: Color,
    val paperSunken: Color,
    val cardWhite: Color,

    val borderSoft: Color,
    val borderStrong: Color,
    val borderDashed: Color,

    val ink: Color,
    val inkSecondary: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val inkDisabled: Color,

    val violet: Color,
    val violetDeep: Color,
    val violetGradientTop: Color,
    val violetGradientBottom: Color,
    val violetRing: Color,
    val violetTint: Color,
    val violetTintStrong: Color,

    val green: Color,
    val greenInk: Color,
    val greenTint: Color,
    val greenBorder: Color,

    val orange: Color,
    val orangeInk: Color,
    val orangeDue: Color,
    val orangeTint: Color,
    val orangeBorder: Color,

    val blue: Color,
    val blueInk: Color,
    val blueTint: Color,

    val rose: Color,
    val roseInk: Color,

    val danger: Color,

    /**
     * The ground QOO's tile is drawn on.
     *
     * The art is a black robot lit by amber, rendered on black, so on paper it
     * needs a dark tile or it reads as a smudge. On the QOO palette the page
     * is already that dark, so the tile is set to the card colour and the
     * mascot stops being boxed — he sits directly on the surface. That is the
     * one thing this theme buys that no amount of recolouring on paper can.
     */
    val qooGround: Color,

    /**
     * Draw the mascot with a Screen blend instead of straight over.
     *
     * Screen leaves a black source pixel showing whatever is beneath it, so on
     * a dark ground the art's baked-in backdrop disappears and only the glow
     * survives — a cutout for free, from art that cannot actually be cut out.
     * It is wrong on paper, where it would wash the character to nothing, so
     * it rides with the palette rather than being a property of the tile.
     */
    val blendMascot: Boolean,

    /** Status-bar icons: dark on a light ground, light on a dark one. */
    val lightStatusBarIcons: Boolean,

    val personTints: List<Pair<Color, Color>>
)

/**
 * The shipping identity: warm paper, near-black ink, one violet accent.
 *
 * Values are the originals, moved here verbatim — this palette is what
 * "revert" means, so it has to be the old look exactly.
 */
val PaperPalette = Palette(
    paper = Color(0xFFFAF9F7),
    paperSunken = Color(0xFFF2F0EC),
    cardWhite = Color(0xFFFFFFFF),

    borderSoft = Color(0xFFECE9E3),
    borderStrong = Color(0xFFE6E3DD),
    borderDashed = Color(0xFFD6D1C8),

    ink = Color(0xFF17150F),
    inkSecondary = Color(0xFF5C554B),
    inkMuted = Color(0xFF4A443B),
    inkFaint = Color(0xFF6F675C),
    inkDisabled = Color(0xFFCFC9BE),

    violet = Color(0xFF6C4FD1),
    violetDeep = Color(0xFF5B41B4),
    violetGradientTop = Color(0xFF5843A8),
    violetGradientBottom = Color(0xFF3F3080),
    violetRing = Color(0xFF4C3A9B),
    violetTint = Color(0xFFECEAFF),
    violetTintStrong = Color(0xFFDED9FF),

    green = Color(0xFF3E9E7E),
    greenInk = Color(0xFF2E7761),
    greenTint = Color(0xFFEEF4F0),
    greenBorder = Color(0xFFDBE6DF),

    orange = Color(0xFFC97F45),
    orangeInk = Color(0xFF945A2C),
    orangeDue = Color(0xFFAD6236),
    orangeTint = Color(0xFFFDF1EA),
    orangeBorder = Color(0xFFF6E0D2),

    blue = Color(0xFF4A80C4),
    blueInk = Color(0xFF3A6598),
    blueTint = Color(0xFFEAF0F8),

    rose = Color(0xFFF6E6E0),
    roseInk = Color(0xFF9A5A42),

    danger = Color(0xFFBE3F35),

    qooGround = Color(0xFF14130F),
    blendMascot = false,
    lightStatusBarIcons = false,

    personTints = listOf(
        Color(0xFFECEAFF) to Color(0xFF5B41B4),
        Color(0xFFF6E6E0) to Color(0xFF9A5A42),
        Color(0xFFEEF4F0) to Color(0xFF2E7761),
        Color(0xFFEAF0F8) to Color(0xFF3A6598),
        Color(0xFFDED9FF) to Color(0xFF5B41B4),
        Color(0xFFFDF1EA) to Color(0xFF945A2C)
    )
)

/**
 * QOO's own colours: his body is the ground, his glow is the accent.
 *
 * Taken off the mascot art rather than invented — the grounds are the warm
 * near-blacks he is rendered against, and the accent is the amber of his
 * eye-rings and outline. The accent keeps the violet *names* because every
 * screen already treats "violet" as the accent; renaming the token across 22
 * files would make this a one-way door, and it is meant to be a look worth
 * trying, not a commitment.
 *
 * Two things are deliberately not a mechanical inversion:
 *
 * - Category tints are near-blacks with a hue lean, not darkened pastels.
 *   Darkened pastels go muddy and the four categories start to look alike; a
 *   near-black carrying a trace of the hue stays separable at tag size.
 * - The "ink" tone of each tag is *lighter* than its dot colour here, because
 *   it now sits on a dark tint. On paper that relationship runs the other
 *   way, which is why the paper values cannot simply be reused.
 */
val QooPalette = Palette(
    // Warm near-blacks, not pure black: the mascot's glow has a brown-amber
    // cast, and against true black his own backdrop reads as a lighter patch.
    paper = Color(0xFF0E0D0A),
    paperSunken = Color(0xFF17150F),
    // Cards sit *above* the ground here too, so they lift rather than drop.
    cardWhite = Color(0xFF1C1A14),

    borderSoft = Color(0xFF262219),
    borderStrong = Color(0xFF332D21),
    borderDashed = Color(0xFF433A2A),

    ink = Color(0xFFF7F2E6),
    inkSecondary = Color(0xFFB3A994),
    inkMuted = Color(0xFFC2B8A2),
    inkFaint = Color(0xFF8D8370),
    inkDisabled = Color(0xFF4B4435),

    // The glow. Amber stays warm at small sizes and does not fight the mascot
    // the way a second saturated hue would.
    violet = Color(0xFFFFB020),
    violetDeep = Color(0xFFFFCB63),
    violetGradientTop = Color(0xFF4A3209),
    violetGradientBottom = Color(0xFF1A1206),
    violetRing = Color(0xFFFFB020),
    violetTint = Color(0xFF2B2110),
    violetTintStrong = Color(0xFF3B2D15),

    green = Color(0xFF4FD1A1),
    greenInk = Color(0xFF8FE5C4),
    greenTint = Color(0xFF12251F),
    greenBorder = Color(0xFF1E362D),

    orange = Color(0xFFE8954A),
    orangeInk = Color(0xFFF5B97E),
    orangeDue = Color(0xFFF0A45E),
    orangeTint = Color(0xFF2A1D11),
    orangeBorder = Color(0xFF3A2817),

    blue = Color(0xFF6FA8E8),
    blueInk = Color(0xFFA3C9F2),
    blueTint = Color(0xFF141E2A),

    rose = Color(0xFF2E1C16),
    roseInk = Color(0xFFE8A98C),

    danger = Color(0xFFE5544A),

    // Equal to cardWhite, which is what un-boxes the mascot — see [Palette].
    qooGround = Color(0xFF1C1A14),
    blendMascot = true,
    lightStatusBarIcons = true,

    personTints = listOf(
        Color(0xFF2B2110) to Color(0xFFFFCB63),
        Color(0xFF2E1C16) to Color(0xFFE8A98C),
        Color(0xFF12251F) to Color(0xFF8FE5C4),
        Color(0xFF141E2A) to Color(0xFFA3C9F2),
        Color(0xFF3B2D15) to Color(0xFFFFCB63),
        Color(0xFF2A1D11) to Color(0xFFF5B97E)
    )
)

/**
 * The palette every colour name resolves through.
 *
 * Deliberately a plain global rather than a CompositionLocal: these colours
 * are also read outside composition (notification builders, and anything that
 * runs before a frame), and a local would hand those the default instead of
 * the user's choice.
 */
var current: Palette by mutableStateOf(PaperPalette)
    private set

/** Remembers the choice across launches, and applies it. */
object ThemePreference {

    private const val PREFS = "dailyflow_theme"
    private const val KEY_QOO = "qoo_theme"

    /**
     * Call before the first frame. Applying it later also works — the switch
     * is live — but the first frame would paint in the wrong palette and
     * flash on every cold start.
     */
    fun load(context: Context) {
        current = if (isQoo(context)) QooPalette else PaperPalette
    }

    fun isQoo(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_QOO, false)

    fun set(context: Context, qoo: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_QOO, qoo)
            .apply()
        current = if (qoo) QooPalette else PaperPalette
    }
}
