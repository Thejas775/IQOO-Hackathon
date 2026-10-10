package com.knoxx.dailyflow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.data.Direction
import com.knoxx.dailyflow.ui.theme.BadgeLabel
import com.knoxx.dailyflow.ui.theme.BorderSoft
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkDisabled
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Orange
import com.knoxx.dailyflow.ui.theme.OrangeBorder
import com.knoxx.dailyflow.ui.theme.OrangeInk
import com.knoxx.dailyflow.ui.theme.OrangeTint
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.PillShape
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.personTints
import kotlin.math.abs

/**
 * The shared vocabulary every DailyFlow screen is assembled from. Defining
 * these once is what keeps a commitment card on Today identical to the same
 * card on the Person screen — in the design they are literally one object.
 */

/** Tap target for surfaces that aren't Buttons. Uses the theme's own
 *  indication rather than an explicit ripple, so it needs no extra artifact
 *  and won't break when material3's ripple API moves again. */
fun Modifier.flowClickable(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

/**
 * A white card on the paper ground.
 *
 * The hairline border does the work, not elevation. The design lifts cards
 * with very soft, wide shadows; a stock Material elevation would read as a
 * chunkier, more generic product.
 */
@Composable
fun FlowCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    background: Color = CardWhite,
    border: Color = BorderSoft,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape),
        content = content
    )
}

/** The quieter panel: stat tiles, the privacy note, inactive chips. */
@Composable
fun SunkenPanel(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    content: @Composable ColumnScope.() -> Unit
) = FlowCard(modifier, shape, PaperSunken, BorderStrong, content)

/**
 * "COMMITMENTS · 2 OPEN" with an optional action ("View all", "+ Add").
 * Baseline-aligned: the label is mono and the action is sans, so aligning
 * their tops would leave them visibly off.
 */
@Composable
fun SectionHeader(
    label: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom
    ) {
        Text(label.uppercase(), style = Eyebrow, color = InkSecondary)
        if (action != null) {
            Text(
                action,
                style = MaterialTheme.typography.labelMedium,
                color = Violet,
                modifier = if (onAction != null) Modifier.flowClickable(onAction) else Modifier
            )
        }
    }
}

/**
 * "You promised" / "Shreya asked you". A filled dot plus a mono label — the
 * dot is what makes it read as a status rather than a tappable chip.
 * Obligations get the orange treatment; everything else stays neutral.
 */
@Composable
fun DirectionPill(
    direction: Direction,
    person: String?,
    modifier: Modifier = Modifier
) {
    val obligation = direction.isObligation
    val tint = if (obligation) OrangeTint else PaperSunken
    val border = if (obligation) OrangeBorder else BorderStrong
    val ink = if (obligation) OrangeInk else InkSecondary
    val dot = if (obligation) Orange else InkFaint

    Row(
        modifier = modifier
            .clip(PillShape)
            .background(tint)
            .border(1.dp, border, PillShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(dot))
        Text(direction.label(person).uppercase(), style = BadgeLabel, color = ink)
    }
}

/** "Work" / "Personal" / "Home" — tinted background, matching ink. */
@Composable
fun CategoryTag(category: String?, modifier: Modifier = Modifier) {
    val c = categoryColorsFor(category)
    Text(
        text = c.label,
        style = MaterialTheme.typography.labelSmall,
        color = c.ink,
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(c.tint)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/**
 * The uppercase app badge on a timeline row: a colour swatch plus the name.
 *
 * [computer] is the paired PC's name when the memory was made there and
 * synced to this phone; it adds a second chip, so every card says which
 * device actually saw the thing rather than implying the phone did.
 */
@Composable
fun SourceBadge(packageName: String?, modifier: Modifier = Modifier, computer: String? = null) {
    val app = sourceAppFor(packageName)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(app.tint)
                .padding(start = 5.dp, end = 7.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(Modifier.size(11.dp).clip(RoundedCornerShape(3.dp)).background(app.ink))
            Text(app.label.uppercase(), style = BadgeLabel, color = app.ink)
        }
        if (computer != null) {
            Text(
                "FROM ${computer.uppercase().take(18)}",
                style = BadgeLabel,
                color = com.knoxx.dailyflow.ui.theme.VioletDeep,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(com.knoxx.dailyflow.ui.theme.VioletTint)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

/** The PC a memory came from, or null when this phone saw it itself. */
val com.knoxx.dailyflow.data.MemoryEntity.computerName: String?
    get() = if (fromComputer) (originName ?: "PC") else null

/**
 * Initial in a circle. The tone is derived from the name hash so one person
 * keeps the same colour on Today, Calendar and their own page — an avatar
 * that changed colour per screen would read as a different person.
 */
@Composable
fun PersonAvatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    shape: Shape = CircleShape
) {
    val index = if (name.isEmpty()) 0 else abs(name.hashCode()) % personTints.size
    val (tint, ink) = personTints[index]
    Box(
        modifier = modifier.size(size).clip(shape).background(tint),
        contentAlignment = Alignment.Center
    ) {
        Text(
            name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            fontWeight = FontWeight(800)
        )
    }
}

/** Dark filled chip — the primary action on a card ("Draft follow-up"). */
@Composable
fun PrimaryChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Paper,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(Ink)
            .flowClickable(onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

/** Outlined chip — the secondary action ("Remind Thursday", "Mark done"). */
@Composable
fun SecondaryChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = InkSecondary,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(PaperSunken)
            .border(1.dp, BorderStrong, MaterialTheme.shapes.small)
            .flowClickable(onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    )
}

/** One of the Yesterday / Today / Tomorrow tiles. The middle one inverts. */
@Composable
fun StatTile(
    label: String,
    value: String,
    emphasised: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .clip(shape)
            .background(if (emphasised) Ink else PaperSunken)
            .then(if (emphasised) Modifier else Modifier.border(1.dp, BorderStrong, shape))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasised) Color(0xFFCFC9BD) else InkSecondary,
            fontWeight = FontWeight(if (emphasised) 700 else 600)
        )
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            color = if (emphasised) Paper else Color(0xFF55504A),
            fontWeight = FontWeight(if (emphasised) 800 else 700)
        )
    }
}

/** Dashed-outline placeholder used for "Add" in the people row. */
@Composable
fun AddTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    Column(
        modifier = modifier
            .clip(shape)
            .background(PaperSunken)
            .border(1.dp, InkDisabled, shape)
            .flowClickable(onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(Paper)
                .border(1.dp, BorderStrong, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium, color = InkFaint)
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary,
            fontWeight = FontWeight(700)
        )
    }
}

@Composable
fun VSpace(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HSpace(width: Dp) = Spacer(Modifier.width(width))
