package com.knoxx.dailyflow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DailyFlow's palette, converted from the oklch values in the redesign's
 * `.dc.html` screens to sRGB, since Compose has no oklch literal.
 *
 * (Note for future edits: Kotlin block comments NEST, so a literal glob like
 * a slash-star inside a KDoc opens a nested comment and swallows the rest of
 * the file. Spell such paths out in words instead.)
 *
 * This is a warm *paper* identity: a light ground with near-black ink and a
 * single violet accent. It deliberately replaces Wick's dark charcoal + flame
 * amber system rather than adapting it — the two are inverses, and every
 * surface, border and text tone here assumes a light ground.
 */

// ── Ground and surfaces ──────────────────────────────────────────────────
/** App background. Warm off-white, never pure #FFFFFF. */
val Paper = Color(0xFFFAF9F7)
/** One step down from the ground: stat tiles, inactive chips, quiet panels. */
val PaperSunken = Color(0xFFF2F0EC)
/** Cards sit *above* the ground, so they are the only true white. */
val CardWhite = Color(0xFFFFFFFF)

val BorderSoft = Color(0xFFECE9E3)   // card hairline
val BorderStrong = Color(0xFFE6E3DD) // sunken panels, dividers
val BorderDashed = Color(0xFFD6D1C8) // "Add" placeholder tiles

// ── Ink ──────────────────────────────────────────────────────────────────
val Ink = Color(0xFF17150F)          // headings, primary text — near-black, warm
val InkSecondary = Color(0xFF5C554B) // supporting copy
val InkMuted = Color(0xFF4A443B)     // nav labels, attribution
val InkFaint = Color(0xFF6F675C)     // timestamps, source labels
val InkDisabled = Color(0xFFCFC9BE)  // unchecked boxes, inactive dots

// ── Accent: violet ───────────────────────────────────────────────────────
// oklch(0.52 0.17 288) and friends. Used for the Today tab, links, the
// morning-brief gradient, and the Work category.
val Violet = Color(0xFF6C4FD1)          // oklch(0.52 0.17 288)
val VioletDeep = Color(0xFF5B41B4)      // oklch(0.45 0.16 288) — text on tint
val VioletGradientTop = Color(0xFF5843A8)    // oklch(0.42 0.16 288)
val VioletGradientBottom = Color(0xFF3F3080) // oklch(0.32 0.13 292)
val VioletRing = Color(0xFF4C3A9B)      // oklch(0.37 0.15 289) — brief ring centre
val VioletTint = Color(0xFFECEAFF)
val VioletTintStrong = Color(0xFFDED9FF)

// ── Semantic categories ──────────────────────────────────────────────────
// Each is a dot colour, a tint background, and an ink for text on that tint.
// Kept separate from the accent so a category never reads as "selected".
val Green = Color(0xFF3E9E7E)        // oklch(0.55 0.13 162) — Personal
val GreenInk = Color(0xFF2E7761)     // oklch(0.44 0.10 162)
val GreenTint = Color(0xFFEEF4F0)
val GreenBorder = Color(0xFFDBE6DF)

val Orange = Color(0xFFC97F45)       // oklch(0.58 0.14 45) — Promise / commitment
val OrangeInk = Color(0xFF945A2C)    // oklch(0.45 0.11 45)
val OrangeDue = Color(0xFFAD6236)    // oklch(0.52 0.14 40) — due-date text
val OrangeTint = Color(0xFFFDF1EA)
val OrangeBorder = Color(0xFFF6E0D2)

val Blue = Color(0xFF4A80C4)         // oklch(0.60 0.13 240) — Home
val BlueInk = Color(0xFF3A6598)      // oklch(0.45 0.11 245)
val BlueTint = Color(0xFFEAF0F8)

val Rose = Color(0xFFF6E6E0)         // person-avatar tint
val RoseInk = Color(0xFF9A5A42)      // oklch(0.42 0.11 40)

val Danger = Color(0xFFBE3F35)       // oklch(0.50 0.15 25) — "Erase all"

// ── Person avatar tones ──────────────────────────────────────────────────
/**
 * Drawn from the category tints rather than a hue wheel, so a row of avatars
 * reads as part of this palette instead of generic colourful chips. Wick had
 * the same intent but in its amber family — these are retoned, not reused.
 */
val personTints = listOf(
    VioletTint to VioletDeep,
    Rose to RoseInk,
    GreenTint to GreenInk,
    BlueTint to BlueInk,
    VioletTintStrong to VioletDeep,
    OrangeTint to OrangeInk
)
