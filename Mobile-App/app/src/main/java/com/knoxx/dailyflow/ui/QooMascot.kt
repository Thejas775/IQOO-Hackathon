package com.knoxx.dailyflow.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.R
import kotlin.math.roundToInt

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
 * The mascot, on a tile or dissolved into the page.
 *
 * The art is a black robot lit by amber glow, rendered on black. Its body and
 * its backdrop are the same colour, so no cutout keeps the character —
 * removing the background removes most of QOO with it. Two answers, one per
 * identity:
 *
 * - On paper there is no hiding it, so the dark ground is made deliberate: a
 *   tile. The Morning Brief is already a dark gradient card, so the tile reads
 *   as part of that system rather than a foreign object dropped onto paper.
 * - On the QOO palette the art is drawn with a Screen blend, where a black
 *   source pixel leaves whatever is beneath it untouched. The backdrop
 *   evaporates into the card and only the glow is left — the cutout the file
 *   itself cannot provide.
 *
 * Both paths draw through one Canvas rather than branching between an Image
 * and a Canvas, so the mascot is laid out identically either way and swapping
 * the theme cannot shift him by a pixel.
 */
@Composable
fun QooTile(
    qoo: Qoo,
    modifier: Modifier = Modifier,
    size: Dp = 96.dp
) {
    val palette = com.knoxx.dailyflow.ui.theme.current
    val art = ImageBitmap.imageResource(qoo.art)

    Canvas(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.large)
    ) {
        // Drawn here rather than via Modifier.background because Screen has to
        // blend against something inside this same draw pass — a modifier
        // background sits in a different layer and the blend would find
        // transparent black and swallow the mascot whole.
        drawRect(palette.qooGround)

        // Fit, by hand: the poses were trimmed to their own ink and are not
        // square, so scaling by the smaller ratio is what keeps a wide pose
        // from being cropped at the ears.
        val scale = minOf(this.size.width / art.width, this.size.height / art.height)
        val drawn = IntSize((art.width * scale).roundToInt(), (art.height * scale).roundToInt())

        drawImage(
            image = art,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(art.width, art.height),
            dstOffset = IntOffset(
                ((this.size.width - drawn.width) / 2).roundToInt(),
                ((this.size.height - drawn.height) / 2).roundToInt()
            ),
            dstSize = drawn,
            // The source art is ~250px drawn at up to 108dp, so it is being
            // scaled down on every device here; High costs nothing at this
            // size and keeps the glow edges from crawling.
            filterQuality = FilterQuality.High,
            blendMode = if (palette.blendMascot) BlendMode.Screen else BlendMode.SrcOver
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

// The tile ground moved into the palette: on the QOO identity it equals the
// card colour, so the tile stops reading as a box and the mascot sits
// directly on the surface. See Palette.qooGround.
