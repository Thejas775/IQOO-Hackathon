package com.knoxx.dailyflow.ui

import androidx.compose.ui.graphics.Color
import com.knoxx.dailyflow.ui.theme.Blue
import com.knoxx.dailyflow.ui.theme.BlueInk
import com.knoxx.dailyflow.ui.theme.BlueTint
import com.knoxx.dailyflow.ui.theme.Green
import com.knoxx.dailyflow.ui.theme.GreenInk
import com.knoxx.dailyflow.ui.theme.GreenTint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Orange
import com.knoxx.dailyflow.ui.theme.OrangeInk
import com.knoxx.dailyflow.ui.theme.OrangeTint
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletDeep
import com.knoxx.dailyflow.ui.theme.VioletTint

/**
 * The four categories Gemma is allowed to assign, each as a triple of
 * timeline-dot colour, tag background, and tag ink.
 *
 * A null category renders as "Other" rather than being hidden — the extraction
 * prompt permits null, and a tag that silently vanishes reads as a rendering
 * bug rather than as the model declining to guess.
 */
data class CategoryColors(
    val label: String,
    val dot: Color,
    val tint: Color,
    val ink: Color
)

private val Work = CategoryColors("Work", Violet, VioletTint, VioletDeep)
private val Personal = CategoryColors("Personal", Green, GreenTint, GreenInk)
private val Home = CategoryColors("Home", Blue, BlueTint, BlueInk)
private val Other = CategoryColors("Other", InkSecondary, PaperSunken, InkSecondary)

/** Used by the Calendar legend, where "Promise" is a category-like filter
 *  even though it comes from [com.knoxx.dailyflow.data.Direction], not the
 *  category field. */
val PromiseColors = CategoryColors("Promise", Orange, OrangeTint, OrangeInk)

fun categoryColorsFor(category: String?): CategoryColors =
    when (category?.trim()?.lowercase()) {
        "work" -> Work
        "personal" -> Personal
        "home" -> Home
        else -> Other
    }
