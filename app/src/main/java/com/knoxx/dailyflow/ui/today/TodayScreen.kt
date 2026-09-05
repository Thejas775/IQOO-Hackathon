package com.knoxx.dailyflow.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.data.Direction
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.ui.AddTile
import com.knoxx.dailyflow.ui.CategoryTag
import com.knoxx.dailyflow.ui.DateLabels
import com.knoxx.dailyflow.ui.DirectionPill
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PersonAvatar
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.SourceBadge
import com.knoxx.dailyflow.ui.StatTile
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.categoryColorsFor
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.BorderSoft
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkDisabled
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.OrangeDue
import com.knoxx.dailyflow.ui.theme.SourceQuote
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletTint

@Composable
fun TodayScreen(
    state: TodayState,
    onToggleDone: (MemoryEntity) -> Unit,
    onOpenPerson: (String) -> Unit,
    onAddTask: () -> Unit,
    onOpenAsk: () -> Unit,
    onManageMemory: () -> Unit,
    modelMissing: Boolean,
    onGetModel: () -> Unit,
    briefText: String?,
    onPlayBrief: () -> Unit,
    onDraftFollowUp: (MemoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Bottom padding clears the floating nav bar, which draws over this
        // list rather than beside it.
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { Header(state) }
        if (modelMissing) {
            item { ModelBanner(onGetModel, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) }
        }
        item { CountRow(state.counts, Modifier.padding(top = 18.dp)) }
        item {
            MorningBrief(
                state = state,
                onAsk = onOpenAsk,
                modifier = Modifier.padding(top = 14.dp),
                briefText = briefText,
                onPlay = onPlayBrief
            )
        }

        if (state.openCommitments.isNotEmpty()) {
            item {
                SectionHeader(
                    "Commitments · ${state.openCommitments.size} open",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)
                )
            }
            items(state.openCommitments, key = { "c${it.id}" }) { memory ->
                CommitmentCard(
                    memory = memory,
                    onDone = { onToggleDone(memory) },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp),
                    onDraftFollowUp = { onDraftFollowUp(memory) }
                )
            }
        }

        item {
            SectionHeader(
                "Today's timeline",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp),
                action = "+ Add",
                onAction = onAddTask
            )
        }

        if (state.timeline.isEmpty()) {
            item { TimelineEmpty(state.pendingCount, Modifier.padding(20.dp)) }
        } else {
            itemsIndexed(
                items = state.timeline,
                key = { _, memory -> "t${memory.id}" }
            ) { index, memory ->
                TimelineRow(
                    memory = memory,
                    isLast = index == state.timeline.lastIndex,
                    onToggle = { onToggleDone(memory) }
                )
            }
        }

        if (state.people.isNotEmpty()) {
            item {
                SectionHeader(
                    "People in your day",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp),
                    action = "Tap for history"
                )
            }
            item { PeopleRow(state.people, onOpenPerson, onAddTask) }
        }

        item { PrivacyCard(state.memoryCount, onManageMemory, Modifier.padding(20.dp)) }
    }
}

@Composable
private fun Header(state: TodayState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(state.dateLabel.uppercase(), style = Eyebrow, color = InkSecondary)
            Text(
                state.greeting,
                style = MaterialTheme.typography.displaySmall,
                color = Ink
            )
        }
        // No account exists, so the avatar is an identity mark rather than a
        // control — it deliberately does nothing.
        Box(
            Modifier.padding(top = 4.dp).size(38.dp).clip(CircleShape).background(VioletTint),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.labelLarge, color = Violet, fontWeight = FontWeight(700))
        }
    }
}

/**
 * Persistent way back to the download once the sheet has been dismissed.
 *
 * Without the model the app records everything and understands none of it, and
 * the only visible symptom is an empty Today — which reads as broken rather
 * than as unfinished setup. This says which it is.
 */
@Composable
private fun ModelBanner(onGetModel: () -> Unit, modifier: Modifier = Modifier) {
    SunkenPanel(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Gemma isn't installed yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = Ink
                )
                Text(
                    "Screens are being captured, but nothing can be understood until the " +
                        "model is on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            PrimaryChip("Get it", onClick = onGetModel)
        }
    }
}

@Composable
private fun CountRow(counts: DayCounts, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatTile("Yesterday", "${counts.yesterday}", false, Modifier.weight(1f))
        StatTile(
            "Today",
            if (counts.today == 1) "1 moment" else "${counts.today} moments",
            true,
            Modifier.weight(1.35f)
        )
        StatTile("Tomorrow", "${counts.tomorrow}", false, Modifier.weight(1f))
    }
}

@Composable
private fun TimelineEmpty(pending: Int, modifier: Modifier = Modifier) {
    SunkenPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (pending > 0) "Still reading your day" else "Nothing scheduled today",
                style = MaterialTheme.typography.titleSmall,
                color = Ink
            )
            Text(
                // The pending count is surfaced rather than hidden: on-device
                // extraction takes a few seconds per screen, and an empty list
                // with no explanation reads as the app being broken.
                if (pending > 0) {
                    "$pending ${if (pending == 1) "moment" else "moments"} waiting to be understood, " +
                        "on this device."
                } else {
                    "Commitments appear here as they show up in the apps you've allowed."
                },
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
        }
    }
}

@Composable
private fun PeopleRow(
    people: List<String>,
    onOpenPerson: (String) -> Unit,
    onAdd: () -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { AddTile("Add", onAdd, Modifier.width(78.dp)) }
        items(people, key = { it }) { name ->
            PersonTile(name) { onOpenPerson(name) }
        }
    }
}

@Composable
private fun PersonTile(name: String, onClick: () -> Unit) {
    FlowCard(
        modifier = Modifier.width(78.dp).flowClickable(onClick),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PersonAvatar(name)
            Text(
                name,
                style = MaterialTheme.typography.bodySmall,
                color = Ink,
                fontWeight = FontWeight(700),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PrivacyCard(memoryCount: Int, onManage: () -> Unit, modifier: Modifier = Modifier) {
    SunkenPanel(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Memory is yours alone",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Ink,
                    fontWeight = FontWeight(700)
                )
                Text(
                    "$memoryCount ${if (memoryCount == 1) "moment" else "moments"} stored on this " +
                        "device. Erasable in one tap.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Text(
                "Manage",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                fontWeight = FontWeight(700),
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(com.knoxx.dailyflow.ui.theme.CardWhite)
                    .border(1.dp, BorderStrong, MaterialTheme.shapes.small)
                    .flowClickable(onManage)
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            )
        }
    }
}
