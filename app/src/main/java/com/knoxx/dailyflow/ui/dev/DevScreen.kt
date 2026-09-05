package com.knoxx.dailyflow.ui.dev

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.ExtractionPrompt
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.work.ReminderTester
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevState(
    val running: Boolean = false,
    val reply: String = "",
    val note: String? = null,
    val elapsedMs: Long = 0
)

/**
 * A place to put a prompt in front of Gemma and read what comes back.
 *
 * Tuning prompt wording otherwise means a fifteen-minute round trip — edit,
 * build, install, capture something, wait for a pass — which is far too slow
 * to iterate against. This makes it seconds, on the same engine and the same
 * settings the pipeline uses.
 */
class DevViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DevState())
    val state: StateFlow<DevState> = _state.asStateFlow()

    fun run(prompt: String) {
        if (_state.value.running || prompt.isBlank()) return
        _state.value = DevState(running = true, note = "Loading model…")
        viewModelScope.launch {
            val startedAt = System.nanoTime()
            val reply = GemmaEngine.complete(getApplication(), prompt)
            val ms = (System.nanoTime() - startedAt) / 1_000_000
            _state.value = DevState(
                running = false,
                reply = reply.orEmpty(),
                note = if (reply == null) "Model unavailable" else null,
                elapsedMs = ms
            )
            DebugLog.log("dev", "prompt run in ${ms}ms, ${reply?.length ?: 0} chars back")
        }
    }

    /**
     * Fills the box with the real extraction prompt for a waiting capture, so
     * what gets tuned is what the pipeline actually sends rather than an
     * approximation of it typed from memory.
     */
    fun loadRealPrompt(onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val row = MemoryRepository.pendingExtraction().firstOrNull()
            if (row == null) {
                _state.value = _state.value.copy(note = "Nothing pending to build a prompt from")
                return@launch
            }
            onLoaded(
                ExtractionPrompt.build(
                    row.packageName,
                    row.timestamp,
                    row.rawText,
                    MemoryRepository.recentPeople(),
                    MemoryRepository.openCommitments().mapNotNull { m ->
                        m.summary?.let { m.id to it }
                    }
                )
            )
        }
    }

    fun sendTestReminders() {
        viewModelScope.launch {
            val count = ReminderTester.fireAll(getApplication())
            _state.value = _state.value.copy(
                note = if (count == 0) "Nothing scheduled — read your day first"
                else "Fired $count reminder(s)"
            )
        }
    }
}

@Composable
fun DevScreen(
    state: DevState,
    tracePath: String,
    onBack: () -> Unit,
    onRun: (String) -> Unit,
    onLoadReal: ((String) -> Unit) -> Unit,
    onTestReminders: () -> Unit,
    modifier: Modifier = Modifier
) {
    var prompt by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Paper).imePadding(),
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
                    Text("<", style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                Text("Tuning", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }

        item {
            SectionHeader("Reminders", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp))
        }
        item {
            SecondaryChip(
                "Send test reminders now",
                onClick = onTestReminders,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            )
        }
        item {
            Text(
                "Fires every scheduled reminder immediately, each showing its real " +
                    "deadline. A preview, not a substitute: they are not consumed, so " +
                    "the genuine nudge still arrives on time.",
                style = MaterialTheme.typography.bodySmall,
                color = InkSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp)
            )
        }

        item {
            SectionHeader("Prompt", Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp))
        }
        item {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("Ask Gemma anything, or load the real extraction prompt") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                minLines = 4,
                maxLines = 12
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondaryChip(
                    "Load real prompt",
                    onClick = { onLoadReal { loaded -> prompt = loaded } },
                    modifier = Modifier.weight(1f)
                )
                PrimaryChip(
                    label = if (state.running) "Running..." else "Run",
                    onClick = { onRun(prompt) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        state.note?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                )
            }
        }

        if (state.reply.isNotEmpty()) {
            item {
                SectionHeader(
                    "Reply - ${state.elapsedMs} ms",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)
                )
            }
            item {
                FlowCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(
                        state.reply,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = Ink,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        item {
            SectionHeader("Trace", Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp))
        }
        item {
            SunkenPanel(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "adb pull $tracePath",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = InkSecondary
                    )
                    Text(
                        "Every capture decision and extraction verdict, with its reason.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkFaint
                    )
                }
            }
        }
    }
}
