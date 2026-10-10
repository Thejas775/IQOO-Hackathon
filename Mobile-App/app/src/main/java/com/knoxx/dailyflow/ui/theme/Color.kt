package com.knoxx.dailyflow.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DailyFlow's palette, by name.
 *
 * (Note for future edits: Kotlin block comments NEST, so a literal glob like
 * a slash-star inside a KDoc opens a nested comment and swallows the rest of
 * the file. Spell such paths out in words instead.)
 *
 * These were literals until the QOO identity was put up for comparison. They
 * are now getters onto [current], so swapping the palette re-themes the whole
 * app without touching any of the 22 files that import these names. The values
 * themselves — and the reasoning behind each one — now live in `Palette.kt`.
 *
 * Getters, not vals: a val would read the palette once at class-init and the
 * switch would do nothing until the process restarted. As getters they are
 * also snapshot reads, which is what makes the switch recompose the UI live.
 */

// ── Ground and surfaces ──────────────────────────────────────────────────
/** App background. Warm off-white on paper, warm near-black on QOO. */
val Paper: Color get() = current.paper
/** One step from the ground: stat tiles, inactive chips, quiet panels. */
val PaperSunken: Color get() = current.paperSunken
/** Cards sit *above* the ground, in both identities. */
val CardWhite: Color get() = current.cardWhite

val BorderSoft: Color get() = current.borderSoft     // card hairline
val BorderStrong: Color get() = current.borderStrong // sunken panels, dividers
val BorderDashed: Color get() = current.borderDashed // "Add" placeholder tiles

// ── Ink ──────────────────────────────────────────────────────────────────
val Ink: Color get() = current.ink                   // headings, primary text
val InkSecondary: Color get() = current.inkSecondary // supporting copy
val InkMuted: Color get() = current.inkMuted         // nav labels, attribution
val InkFaint: Color get() = current.inkFaint         // timestamps, source labels
val InkDisabled: Color get() = current.inkDisabled   // unchecked boxes, inactive dots

// ── Accent ───────────────────────────────────────────────────────────────
// Violet on paper, QOO's amber glow on his own palette. The names stay
// "Violet" because every screen already reads them as *the* accent; see the
// note on [QooPalette] for why renaming them was rejected.
val Violet: Color get() = current.violet
val VioletDeep: Color get() = current.violetDeep           // text on tint
val VioletGradientTop: Color get() = current.violetGradientTop
val VioletGradientBottom: Color get() = current.violetGradientBottom
val VioletRing: Color get() = current.violetRing           // brief ring centre
val VioletTint: Color get() = current.violetTint
val VioletTintStrong: Color get() = current.violetTintStrong

// ── Semantic categories ──────────────────────────────────────────────────
// Each is a dot colour, a tint background, and an ink for text on that tint.
// Kept separate from the accent so a category never reads as "selected".
val Green: Color get() = current.green          // Personal
val GreenInk: Color get() = current.greenInk
val GreenTint: Color get() = current.greenTint
val GreenBorder: Color get() = current.greenBorder

val Orange: Color get() = current.orange        // Promise / commitment
val OrangeInk: Color get() = current.orangeInk
val OrangeDue: Color get() = current.orangeDue  // due-date text
val OrangeTint: Color get() = current.orangeTint
val OrangeBorder: Color get() = current.orangeBorder

val Blue: Color get() = current.blue            // Home
val BlueInk: Color get() = current.blueInk
val BlueTint: Color get() = current.blueTint

val Rose: Color get() = current.rose            // person-avatar tint
val RoseInk: Color get() = current.roseInk

val Danger: Color get() = current.danger        // "Erase all"

// ── Person avatar tones ──────────────────────────────────────────────────
/**
 * Drawn from the category tints rather than a hue wheel, so a row of avatars
 * reads as part of the palette instead of generic colourful chips.
 */
val personTints: List<Pair<Color, Color>> get() = current.personTints
