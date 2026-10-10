package com.knoxx.dailyflow.ui.memories

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.Direction
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ui.CategoryTag
import com.knoxx.dailyflow.ui.DateLabels
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.sourceAppFor
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.PaperSunken
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class MemoriesViewModel(app: Application) : AndroidViewModel(app) {
    val memories: StateFlow<List<MemoryEntity>> =
        MemoryRepository.observeAllMemories()
            .map { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) = viewModelScope.launch { MemoryRepository.delete(id) }
}

/**
 * Everything DailyFlow has understood, newest first.
 *
 * Deliberately shows the source quote rather than only the summary. The
 * summary is the app's paraphrase; the quote is the user's own words, and on
 * a screen whose whole job is "what did it actually record about me?", the
 * paraphrase alone is not an answer.
 */
@Composable
fun MemoriesScreen(
    memories: List<MemoryEntity>,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    Modifier.size(36.dp).clip(MaterialTheme.shapes.medium)
                        .background(PaperSunken).flowClickable(onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Text("‹", style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                Text("Memories", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }

        if (memories.isEmpty()) {
            item {
                Text(
                    "Nothing understood yet. Once a pass has run, everything it " +
                        "recorded shows up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkFaint,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            item {
                SectionHeader(
                    "${memories.size} stored",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
                )
            }
            items(memories, key = { it.id }) { memory ->
                MemoryCard(memory, onDelete = { onDelete(memory.id) })
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: MemoryEntity, onDelete: () -> Unit) {
    // Two taps, because this is the one irreversible action on the screen and
    // the rows are dense enough to hit by accident while scrolling. Local to
    // the card, so arming one row disarms nothing else and no state outlives
    // the screen.
    var confirming by remember { mutableStateOf(false) }

    FlowCard(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    Direction.parse(memory.direction).label(memory.personName),
                    style = MaterialTheme.typography.labelMedium,
                    color = InkSecondary
                )
                Text(
                    // The capture time, not dueAt: this screen is a record of
                    // what was seen and when, and a date-only deadline would
                    // otherwise print the app's own 9 AM anchor.
                    DateLabels.relativeShort(memory.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint
                )
            }

            Text(
                memory.summary.orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = Ink
            )

            memory.sourceQuote?.let { quote ->
                Text(
                    "“$quote”",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                memory.category?.let { CategoryTag(it) }
                Text(
                    sourceAppFor(memory.packageName).label,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint
                )
                if (memory.commitmentDone) {
                    Text(
                        "· done",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint
                    )
                }

                Box(Modifier.weight(1f))

                Text(
                    if (confirming) "Remove?" else "Remove",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (confirming) Danger else InkFaint,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .flowClickable { if (confirming) onDelete() else confirming = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}
