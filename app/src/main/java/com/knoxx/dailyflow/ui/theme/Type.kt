package com.knoxx.dailyflow.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.knoxx.dailyflow.R

/**
 * Two families, bundled as assets — never downloaded.
 *
 * `ui-text-google-fonts` is deliberately not used: it fetches at runtime
 * through Play Services and falls back *silently* when that fails. On an
 * app whose whole claim is working offline, the typography must not depend
 * on a network call. (This is the exact bug found in Vision-AI-Gemma, where
 * the offline reading assistant downloaded its own fonts.)
 */

/**
 * Plus Jakarta Sans ships as a single variable font covering ExtraLight
 * through ExtraBold, so all five weights the design uses come from one
 * ~176 KB file instead of five statics. `FontVariation` needs API 26; minSdk
 * is 29, so this is safe.
 */
@OptIn(ExperimentalTextApi::class)
private fun jakarta(weight: Int) = Font(
    R.font.plus_jakarta_sans,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val Jakarta = FontFamily(
    jakarta(400), jakarta(500), jakarta(600), jakarta(700), jakarta(800)
)

/** IBM Plex Mono has no variable build in Google Fonts, so three statics. */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold)
)

/**
 * The uppercase mono label used throughout the design for section headers,
 * counts, source badges and timestamps — "COMMITMENTS · 2 OPEN",
 * "MORNING BRIEF", "9:00 AM". Wide tracking is what makes it read as a label
 * rather than as small body text, so it is baked in here rather than left to
 * each call site.
 */
val Eyebrow = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.14.em
)

/** Same voice, smaller — inline badges like SLACK / WHATSAPP / MANUAL. */
val BadgeLabel = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.1.em
)

/** Numerals that line up in columns — stat tiles, counts, durations. */
val MonoFigure = TextStyle(
    fontFamily = PlexMono,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 14.sp,
    letterSpacing = 0.04.em
)

/**
 * The quoted evidence line under a commitment — italic, so a captured
 * sentence is visually distinct from DailyFlow's own summary of it. That
 * distinction is the point: the user should never be unsure which words are
 * theirs and which the model wrote.
 */
val SourceQuote = TextStyle(
    fontFamily = Jakarta,
    fontWeight = FontWeight.Medium,
    fontSize = 12.5.sp,
    lineHeight = 18.sp,
    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
)

val DailyFlowTypography = Typography(
    // Greeting: "Good morning, Aarav"
    displaySmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(800),
        fontSize = 26.sp, lineHeight = 29.sp, letterSpacing = (-0.035).em
    ),
    // Screen titles: "May", "Shreya"
    headlineMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(800),
        fontSize = 24.sp, lineHeight = 27.sp, letterSpacing = (-0.03).em
    ),
    headlineSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(800),
        fontSize = 21.sp, lineHeight = 25.sp, letterSpacing = (-0.028).em
    ),
    // The morning-brief sentence
    titleLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.021).em
    ),
    // Commitment titles
    titleMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 16.5.sp, lineHeight = 22.sp, letterSpacing = (-0.018).em
    ),
    // Row titles, timeline entries
    titleSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.013).em
    ),
    bodyLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp, lineHeight = 21.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 13.sp, lineHeight = 19.sp
    ),
    bodySmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight.Medium,
        fontSize = 11.5.sp, lineHeight = 16.sp
    ),
    // Buttons and action chips
    labelLarge = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 13.sp, lineHeight = 17.sp
    ),
    labelMedium = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 12.sp, lineHeight = 16.sp
    ),
    // Category tags: "Work", "Personal", "Home"
    labelSmall = TextStyle(
        fontFamily = Jakarta, fontWeight = FontWeight(700),
        fontSize = 10.5.sp, lineHeight = 14.sp
    )
)
