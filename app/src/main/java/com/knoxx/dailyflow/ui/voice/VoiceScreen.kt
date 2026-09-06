package com.knoxx.dailyflow.ui.voice

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.Direction
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.ExtractionParser
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.llm.VoicePrompt
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.voice.VoiceRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One thing the note asked for, before it is saved. */
data class VoiceItem(
    val summary: String,
    val person: String?,
    val dueAt: Long?,
    val direction: Direction
)

data class VoiceState(
    val recording: Boolean = false,
    val thinking: Boolean = false,
    val items: List<VoiceItem> = emptyList(),
    val saved: Boolean = false,
    val note: String? = null
)

/**
 * Speak a note, get action items.
 *
 * The audio goes straight to Gemma rather than through a speech recogniser
 * first. Android's recogniser is the obvious route and the wrong one here:
 * the on-device variant is not available on every device and the default one
 * sends audio to Google, which would quietly make the "0 cloud calls" counter
 * on the profile screen untrue. The model already on the phone has an audio
 * encoder, so the note never leaves.
 */
class VoiceViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val recorder = VoiceRecorder(app)

    fun startRecording() {
        if (_state.value.recording || _state.value.thinking) return
        if (!recorder.start()) {
            _state.value = VoiceState(note = "Couldn't open the microphone")
            return
        }
        _state.value = VoiceState(recording = true)
        DebugLog.log("voice", "recording started")
    }

    fun stopAndProcess() {
        if (!_state.value.recording) return
        _state.value = _state.value.copy(recording = false, thinking = true, note = "Listening back…")

        viewModelScope.launch {
            val file = recorder.stop()
            if (file == null) {
                _state.value = VoiceState(note = "Nothing was recorded")
                return@launch
            }
            DebugLog.log("voice", "recorded ${file.length()} bytes")

            val reply = GemmaEngine.completeWithAudio(
                getApplication(),
                file.absolutePath,
                VoicePrompt.build()
            )
            if (reply == null) {
                _state.value = VoiceState(note = "The model isn't loaded, so this can't be read yet")
                return@launch
            }

            // Parsed with the same block parser the screen pipeline uses, so a
            // spoken note and a captured screen produce identical structure and
            // one format change cannot make them disagree.
            val items = ExtractionParser.parseAll(reply, "")
                .filter { it.isMemory }
                .map {
                    VoiceItem(
                        summary = it.summary.orEmpty(),
                        person = it.person,
                        dueAt = it.dueAt,
                        direction = it.direction
                    )
                }

            DebugLog.log("voice", "found ${items.size} action items")
            _state.value = VoiceState(
                items = items,
                note = if (items.isEmpty()) "Nothing to act on in that note" else null
            )
        }
    }

    fun cancel() {
        recorder.cancel()
        _state.value = VoiceState()
    }

    /** Saved as manual memories: the owner said these out loud, so they are
     *  theirs rather than something overheard from someone else. */
    fun save() {
        val items = _state.value.items
        if (items.isEmpty()) return
        viewModelScope.launch {
            items.forEach { item ->
                MemoryRepository.addManual(
                    title = item.summary,
                    dueAt = item.dueAt,
                    category = null,
                    personName = item.person
                )
            }
            DebugLog.log("voice", "saved ${items.size} items")
            _state.value = VoiceState(saved = true, note = "Saved ${items.size} to your day")
        }
    }
}

@Composable
fun VoiceScreen(
    state: VoiceState,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSave: () -> Unit,
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
                    Text("<", style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                Text("Speak a note", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }

        item {
            Text(
                "Say what you need to remember. Gemma reads the recording on this " +
                    "device — the audio is never uploaded and never leaves the phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
        }

        item {
            Box(
                Modifier.fillMaxWidth().padding(top = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(if (state.recording) Danger else Violet)
                        .flowClickable { if (state.recording) onStop() else onStart() },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (state.recording) "Stop" else "Record",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
        }

        item {
            Text(
                when {
                    state.recording -> "Listening… tap to stop"
                    state.thinking -> "Reading it back…"
                    else -> state.note ?: "Tap to start"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.recording) Danger else InkFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
            )
        }

        if (state.items.isNotEmpty()) {
            item {
                SectionHeader(
                    "${state.items.size} action item${if (state.items.size == 1) "" else "s"}",
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 30.dp)
                )
            }
            items(state.items) { item ->
                FlowCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(item.summary, style = MaterialTheme.typography.bodyLarge, color = Ink)
                        val detail = listOfNotNull(
                            item.person?.let { "with $it" },
                            item.dueAt?.let { com.knoxx.dailyflow.ui.DateLabels.relativeShort(it) }
                        ).joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(
                                detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkSecondary
                            )
                        }
                    }
                }
            }
            item {
                PrimaryChip(
                    label = "Save to my day",
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            item {
                SecondaryChip(
                    "Record again",
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
                )
            }
        }
    }
}
