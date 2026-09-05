package com.knoxx.dailyflow.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.ui.CategoryTag
import com.knoxx.dailyflow.ui.DateLabels
import com.knoxx.dailyflow.ui.DirectionPill
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PersonAvatar
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SourceBadge
import com.knoxx.dailyflow.ui.categoryColorsFor
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.BorderSoft
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.CommitmentShape
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkDisabled
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkMuted
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.OrangeDue
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.SourceQuote
import com.knoxx.dailyflow.ui.theme.VioletGradientBottom
import com.knoxx.dailyflow.ui.theme.VioletGradientTop

/**
 * The hero card. Until Gemma's synthesis lands it shows a line derived from
 * the day's own numbers rather than a spinner or an empty box — the card is
 * the first thing on the screen and must always say something true.
 */
@Composable
fun MorningBrief(
    state: TodayState,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
    briefText: String? = null,
    onPlay: (() -> Unit)? = null
) {

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(
                Brush.linearGradient(
                    listOf(VioletGradientTop, VioletGradientBottom),
                    start = Offset.Zero,
                    end = Offset(0f, Float.POSITIVE_INFINITY)
                )
            )
            .padding(18.dp)
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp))
                            .background(Color.White.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(Color.White))
                    }
                    Text(
                        "MORNING BRIEF",
                        style = Eyebrow,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }

            Text(
                briefText ?: fallbackBrief(state),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.padding(top = 14.dp)
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BriefButton(
                    label = "Play brief",
                    filled = true,
                    onClick = { onPlay?.invoke() },
                    enabled = onPlay != null,
                    modifier = Modifier.weight(1f)
                )
                BriefButton(
                    label = "Ask memory",
                    filled = false,
                    onClick = onAsk,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * States what is true from the stored data alone, with no model involved.
 * Deliberately never claims more than it knows — the generated brief is
 * allowed to be interpretive, this is not.
 */
private fun fallbackBrief(state: TodayState): String {
    val open = state.openCommitments.size
    val today = state.counts.today
    val moments = if (today == 1) "One moment" else "$today moments"
    return when {
        state.pendingCount > 0 && today == 0 -> "Still reading your day."
        today == 0 && open == 0 -> "Nothing recorded for today yet."
        open == 0 -> "$moments from today, and nothing left open."
        open == 1 -> "$moments from today, with one still open."
        else -> "$moments from today, with $open still open."
    }
}


@Composable
private fun BriefButton(
    label: String,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) Color.White else Color.White.copy(alpha = 0.14f))
            .then(
                if (filled) Modifier
                else Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            )
            .then(if (enabled) Modifier.flowClickable(onClick) else Modifier)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (filled) VioletGradientTop else Color.White.copy(alpha = if (enabled) 1f else 0.5f)
        )
    }
}

/**
 * A commitment, with its evidence.
 *
 * The quoted line is the point of this card. Everything else — the summary,
 * the due date, the person — is DailyFlow's interpretation, and the quote is
 * the one part that is verbatim from the user's own screen. It is only ever
 * present when it was verified against the captured text, so when it appears
 * it can be trusted completely.
 */
@Composable
fun CommitmentCard(
    memory: MemoryEntity,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onDraftFollowUp: (() -> Unit)? = null
) {
    FlowCard(modifier.fillMaxWidth(), shape = CommitmentShape) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DirectionPill(
                    direction = memory.directionOrNull ?: com.knoxx.dailyflow.data.Direction.FROM_THEM,
                    person = memory.personName
                )
                DateLabels.dueLabel(memory.dueAt)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = OrangeDue,
                        fontWeight = FontWeight(700)
                    )
                }
            }

            Text(
                memory.summary.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = Ink,
                modifier = Modifier.padding(top = 11.dp)
            )

            memory.sourceQuote?.let { quote ->
                Row(Modifier.padding(top = 8.dp)) {
                    Box(Modifier.width(2.dp).height(34.dp).background(BorderStrong))
                    Text(
                        "“$quote”",
                        style = SourceQuote,
                        color = InkFaint,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDraftFollowUp != null) {
                    PrimaryChip("Draft follow-up", onDraftFollowUp)
                }
                SecondaryChip(if (memory.commitmentDone) "Undo" else "Mark done", onDone)
                Spacer(Modifier.weight(1f))
                if (memory.packageName.isNotBlank()) {
                    Text(
                        "source ↗",
                        style = com.knoxx.dailyflow.ui.theme.BadgeLabel,
                        color = InkFaint
                    )
                }
            }
        }
    }
}

/**
 * One entry on the day's timeline: time gutter, a coloured node on a
 * continuous rail, then the card.
 *
 * The rail is drawn per row rather than as one line behind the list, because
 * rows are variable height and a single background line would need to know
 * their total extent — which a lazy list cannot tell it.
 */
@Composable
fun TimelineRow(
    memory: MemoryEntity,
    isLast: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = categoryColorsFor(memory.category)
    val (time, meridiem) = memory.dueAt?.let { DateLabels.timelineTime(it) } ?: ("—" to "")

    Row(
        modifier = modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            Modifier.width(52.dp).padding(top = 12.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(time, style = com.knoxx.dailyflow.ui.theme.MonoFigure, color = InkSecondary)
            if (meridiem.isNotEmpty()) {
                Text(meridiem, style = com.knoxx.dailyflow.ui.theme.BadgeLabel, color = InkFaint)
            }
        }

        Column(
            Modifier.width(12.dp).padding(top = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.dot))
            if (!isLast) {
                Box(
                    Modifier.padding(top = 4.dp).width(2.dp).height(72.dp).background(BorderStrong)
                )
            }
        }

        FlowCard(Modifier.weight(1f), shape = MaterialTheme.shapes.large) {
            Row(
                Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(11.dp)
            ) {
                Checkbox(memory.commitmentDone, colors.dot, onToggle)
                Column(Modifier.weight(1f)) {
                    Text(
                        memory.summary.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink,
                        textDecoration = if (memory.commitmentDone) TextDecoration.LineThrough else null
                    )
                    CategoryTag(memory.category, Modifier.padding(top = 6.dp))
                    Attribution(memory, Modifier.padding(top = 9.dp))
                }
            }
        }
    }
}

@Composable
private fun Checkbox(checked: Boolean, tint: Color, onToggle: () -> Unit) {
    Box(
        Modifier
            .padding(top = 1.dp)
            .size(22.dp)
            .clip(RoundedCornerShape(7.dp))
            .background(if (checked) tint else Paper)
            .then(if (checked) Modifier else Modifier.border(2.dp, InkDisabled, RoundedCornerShape(7.dp)))
            .flowClickable(onToggle),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text("✓", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight(800))
        }
    }
}

/** "From Rahul · SLACK" — who it came from, and where from. */
@Composable
private fun Attribution(memory: MemoryEntity, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        val person = memory.personName
        if (person != null) {
            PersonAvatar(person, size = 18.dp)
            Text(
                "From $person",
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
                fontWeight = FontWeight(600)
            )
        } else {
            Text(
                "Added by you",
                style = MaterialTheme.typography.bodySmall,
                color = InkMuted,
                fontWeight = FontWeight(600)
            )
        }
        Box(Modifier.size(3.dp).clip(CircleShape).background(InkDisabled))
        SourceBadge(memory.packageName.ifBlank { null })
    }
}
