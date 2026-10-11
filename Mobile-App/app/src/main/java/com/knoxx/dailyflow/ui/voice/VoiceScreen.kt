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
import androidx.compose.foundation.layout.heightIn
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
import com.knoxx.dailyflow.ui.Qoo
import com.knoxx.dailyflow.ui.QooTile
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
    /** True when the note actually said an hour, false when the parser
     *  anchored a bare date to 9 AM. Carried through to the memory so a
     *  calendar-aware reply only checks times someone really proposed. */
    val dueAtExplicit: Boolean,
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
                        dueAtExplicit = it.dueAtExplicit,
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
                    personName = item.person,
                    // Spoken notes do carry real times — "call the plumber at
                    // four" — so a due time parsed from speech is a stated
                    // one, unlike the Add sheet's day chips.
                    dueAtExplicit = item.dueAtExplicit,
                    // The parsed direction was being dropped here, so every spoken
                    // promise stored as MANUAL. The open-commitments query selects on
                    // SELF_PROMISE / THEY_ASKED, so a voice note could reach the
                    // timeline but never Commitments, however plainly it was a promise.
                    direction = item.direction
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
    Column(modifier = modifier.fillMaxSize().background(Paper)) {
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

        Text(
            "Say what you need to remember. Gemma reads the recording on this " +
                "device — the audio is never uploaded and never leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
        )

        // The button takes the whole space between the header and the results
        // and centres itself in it, rather than sitting under the text at a
        // fixed offset. It is the only control on the screen and the one thing
        // the user came here to press, so it belongs in the middle of the
        // space rather than at the top of it.
        //
        // weight(1f) is what makes it centre in whatever is left over, so the
        // button stays put as results appear beneath it rather than being
        // pushed around by them.
        Box(
            Modifier.fillMaxWidth().weight(1f),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // The pose is the recording state, so it is readable from
                // across a room while the phone is on a desk taking a note —
                // which is how this screen actually gets used. The small text
                // under the button says the same thing, but only to someone
                // already holding it.
                QooTile(
                    when {
                        state.recording -> Qoo.Running
                        state.thinking -> Qoo.Thinking
                        else -> Qoo.Waving
                    },
                    size = 92.dp
                )
                Box(
                    Modifier
                        .size(140.dp)
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
                Text(
                    when {
                        state.recording -> "Listening… tap to stop"
                        state.thinking -> "Reading it back…"
                        else -> state.note ?: "Tap to start"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.recording) Danger else InkFaint,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )
            }
        }

        VoiceResults(state, onSave, onStart)
    }
}

/**
 * The items Gemma heard, below the button.
 *
 * Given its own height budget rather than sharing the button's, so a long list
 * scrolls within itself instead of pushing the record button off the top of
 * the screen — the button has to stay reachable to record again.
 */
@Composable
private fun VoiceResults(
    state: VoiceState,
    onSave: () -> Unit,
    onStart: () -> Unit
) {
    if (state.items.isEmpty()) return

    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        contentPadding = PaddingValues(bottom = 30.dp)
    ) {
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
