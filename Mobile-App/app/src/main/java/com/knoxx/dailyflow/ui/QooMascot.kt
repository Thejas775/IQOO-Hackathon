package com.knoxx.dailyflow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.R

/**
 * QOO, in whichever state the app is actually in.
 *
 * The poses are not decoration — each one maps to something the app is doing,
 * so the mascot doubles as a status indicator. A mascot that appears on every
 * screen becomes wallpaper and stops being read; one that turns up when
 * something is happening keeps meaning something.
 */
enum class Qoo(val art: Int) {
    /** Reading the day — an extraction pass is running. */
    Thinking(R.drawable.qoo_thinking),

    /** Nothing captured yet, or nothing left to do. */
    Sleeping(R.drawable.qoo_sleeping),

    /** Everything read and nothing outstanding. */
    Resting(R.drawable.qoo_resting),

    /** Greeting — first run, and the voice screen. */
    Waving(R.drawable.qoo_waving),

    /** Something is in flight: a download, a sync. */
    Running(R.drawable.qoo_running)
}

/**
 * The mascot on its own dark tile.
 *
 * The art is a black robot lit by amber glow, photographed on black. Its body
 * and its backdrop are the same colour, so there is no cutout that keeps the
 * character — removing the background would remove most of QOO with it. The
 * tile is the answer: the dark ground stays, and becomes deliberate rather
 * than an artefact.
 *
 * That also lets it sit in a light app without fighting it. The Morning Brief
 * is already a dark gradient card, so a dark tile reads as part of the same
 * system rather than a foreign object dropped onto paper.
 */
@Composable
fun QooTile(
    qoo: Qoo,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.large)
            // Not pure black: a few points of lift separates the tile from the
            // art's own backdrop, so the mascot reads as sitting *in* something
            // rather than being a hole in the page.
            .background(QooGround),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(qoo.art),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * QOO with a line of its own, for empty states.
 *
 * [headline] says what is true; [detail] says what happens next. Kept as two
 * strings rather than one so an empty state never becomes a paragraph the
 * user has to read to learn that nothing has happened.
 */
@Composable
fun QooSays(
    qoo: Qoo,
    headline: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 108.dp
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        QooTile(qoo, size = size)
        Text(
            headline,
            style = MaterialTheme.typography.titleSmall,
            color = com.knoxx.dailyflow.ui.theme.Ink,
            textAlign = TextAlign.Center
        )
        detail?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = com.knoxx.dailyflow.ui.theme.InkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

/** QOO beside a line, for a row that already has its own text. */
@Composable
fun QooRow(
    qoo: Qoo,
    text: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        QooTile(qoo, size = 56.dp)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = com.knoxx.dailyflow.ui.theme.InkSecondary
        )
    }
}

/** Near-black, slightly lifted — see [QooTile]. */
private val QooGround = Color(0xFF14130F)
