package com.knoxx.dailyflow.ui.person

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.data.PersonStats
import com.knoxx.dailyflow.ui.DateLabels
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PersonAvatar
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.SourceBadge
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Green
import com.knoxx.dailyflow.ui.theme.GreenInk
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.OrangeDue
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.VioletGradientBottom
import com.knoxx.dailyflow.ui.theme.VioletGradientTop
import com.knoxx.dailyflow.ui.today.CommitmentCard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class PersonState(
    val name: String = "",
    val stats: PersonStats? = null,
    val commitments: List<MemoryEntity> = emptyList(),
    val interactions: List<MemoryEntity> = emptyList(),
    val relationshipLine: String? = null
)

class PersonViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PersonState())
    val state: StateFlow<PersonState> = _state.asStateFlow()

    private var job: Job? = null

    fun load(name: String) {
        if (_state.value.name == name && job?.isActive == true) return
        _state.value = PersonState(name = name)

        job?.cancel()
        job = viewModelScope.launch {
            combine(
                MemoryRepository.observeByPerson(name),
                MemoryRepository.observePersonStats(name)
            ) { memories, stats -> memories to stats }
                .collect { (memories, stats) ->
                    // Split by obligation rather than by date: a commitment is
                    // something still owed, an interaction is everything else
                    // that happened with this person.
                    val (commitments, interactions) = memories.partition {
                        it.directionOrNull?.isObligation == true && !it.commitmentDone
                    }
                    _state.value = _state.value.copy(
                        stats = stats,
                        commitments = commitments,
                        interactions = interactions
                    )
                }
        }
    }

    fun toggleDone(memory: MemoryEntity) {
        viewModelScope.launch { MemoryRepository.setDone(memory.id, !memory.commitmentDone) }
    }
}

@Composable
fun PersonScreen(
    state: PersonState,
    onBack: () -> Unit,
    onToggleDone: (MemoryEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCommitments by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 60.dp)
    ) {
        item { TopBar(onBack) }
        item { Identity(state) }
        item { StatsRow(state, Modifier.padding(top = 16.dp)) }
        item { RelationshipCard(state, Modifier.padding(top = 16.dp)) }

        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Toggle(
                    "Commitments · ${state.commitments.size}",
                    showCommitments,
                    Modifier.weight(1f)
                ) { showCommitments = true }
                Toggle(
                    "Interactions · ${state.interactions.size}",
                    !showCommitments,
                    Modifier.weight(1f)
                ) { showCommitments = false }
            }
        }

        val rows = if (showCommitments) state.commitments else state.interactions
        if (rows.isEmpty()) {
            item {
                Text(
                    if (showCommitments) "Nothing outstanding with ${state.name}."
                    else "No other moments with ${state.name} yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSecondary,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            items(rows, key = { "p${it.id}" }) { memory ->
                if (showCommitments) {
                    CommitmentCard(
                        memory = memory,
                        onDone = { onToggleDone(memory) },
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                    )
                } else {
                    InteractionRow(memory, Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp))
                }
            }
        }

        item { PrivacyNote(state.name, Modifier.padding(20.dp)) }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp)
    ) {
        Box(
            Modifier.size(36.dp).clip(MaterialTheme.shapes.medium).background(PaperSunken)
                .flowClickable(onBack),
            contentAlignment = Alignment.Center
        ) {
            Text("‹", style = MaterialTheme.typography.titleMedium, color = Ink)
        }
    }
}

@Composable
private fun Identity(state: PersonState) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PersonAvatar(state.name, size = 62.dp, shape = RoundedCornerShape(22.dp))
        Column {
            Text(state.name, style = MaterialTheme.typography.headlineMedium, color = Ink)
            Text(
                "${state.stats?.total ?: 0} moments stored",
                style = Eyebrow,
                color = InkSecondary,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}

@Composable
private fun StatsRow(state: PersonState, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatBox("${state.stats?.openCount ?: 0}", "Open", OrangeDue, Modifier.weight(1f))
        StatBox("${state.stats?.kept ?: 0}", "Kept", GreenInk, Modifier.weight(1f))
        StatBox(DateLabels.relativeShort(state.stats?.lastAt), "Last talked", Ink, Modifier.weight(1f))
    }
}

@Composable
private fun StatBox(value: String, label: String, tint: Color, modifier: Modifier = Modifier) {
    FlowCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = tint,
                fontWeight = FontWeight(800)
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                fontWeight = FontWeight(600),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * The one interpretive element on this screen. Until Gemma's synthesis lands
 * it states what the counts already show, rather than inventing a
 * characterisation of a relationship from data the app has not analysed.
 */
@Composable
private fun RelationshipCard(state: PersonState, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(MaterialTheme.shapes.large)
            .background(
                Brush.linearGradient(
                    listOf(VioletGradientTop, VioletGradientBottom),
                    start = Offset.Zero,
                    end = Offset(0f, Float.POSITIVE_INFINITY)
                )
            )
            .padding(16.dp)
    ) {
        Column {
            Text("GEMMA · OFFLINE RECALL", style = Eyebrow, color = Color.White.copy(alpha = 0.72f))
            Text(
                state.relationshipLine ?: fallbackLine(state),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(top = 9.dp)
            )
        }
    }
}

private fun fallbackLine(state: PersonState): String {
    val open = state.stats?.openCount ?: 0
    val kept = state.stats?.kept ?: 0
    return when {
        open == 0 && kept == 0 -> "Nothing outstanding with ${state.name}."
        open == 0 -> "Nothing open with ${state.name}. $kept kept so far."
        open == 1 -> "One thing is still open with ${state.name}."
        else -> "$open things are still open with ${state.name}."
    }
}

@Composable
private fun Toggle(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        PrimaryChip(label, onClick, modifier)
    } else {
        SecondaryChip(label, onClick, modifier)
    }
}

@Composable
private fun InteractionRow(memory: MemoryEntity, modifier: Modifier = Modifier) {
    FlowCard(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(MaterialTheme.shapes.medium).background(PaperSunken),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Green))
            }
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        memory.summary.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        DateLabels.relativeShort(memory.timestamp),
                        style = MonoFigure,
                        color = InkFaint
                    )
                }
                memory.sourceQuote?.let {
                    Text(
                        "“$it”",
                        style = com.knoxx.dailyflow.ui.theme.SourceQuote,
                        color = InkSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                SourceBadge(
                    memory.packageName.ifBlank { null },
                    Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PrivacyNote(name: String, modifier: Modifier = Modifier) {
    SunkenPanel(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 15.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(Green))
            Text(
                "Everything about $name stays on this device. Nothing about them was ever uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary
            )
        }
    }
}
