package com.knoxx.dailyflow.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Notably rounder than Material3's defaults, and much rounder than Wick's
 * deliberately architectural 4–20dp scale. The redesign leans soft — 22dp
 * commitment cards, 18dp timeline rows, 999dp pills — which is what keeps a
 * dense screen of stacked cards feeling calm rather than filed.
 */
val DailyFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),   // badges, small tags
    small = RoundedCornerShape(11.dp),       // action chips
    medium = RoundedCornerShape(14.dp),      // buttons, stat tiles
    large = RoundedCornerShape(20.dp),       // list cards
    extraLarge = RoundedCornerShape(24.dp)   // hero cards (morning brief)
)

/** Fully rounded — direction pills ("You promised"), status chips. */
val PillShape = RoundedCornerShape(999.dp)

/** Commitment cards sit a step above list cards in the hierarchy. */
val CommitmentShape = RoundedCornerShape(22.dp)

/** The Calendar day sheet: rounded at the top, following the device corners
 *  at the bottom so it reads as anchored to the screen edge. */
val DaySheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)
