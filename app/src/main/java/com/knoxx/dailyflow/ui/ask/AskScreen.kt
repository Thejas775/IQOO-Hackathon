package com.knoxx.dailyflow.ui.ask

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.llm.AskPrompt
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.llm.MemorySearch
import com.knoxx.dailyflow.ml.EmbeddingEngine
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.BadgeLabel
import com.knoxx.dailyflow.ui.theme.BorderSoft
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Green
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkMuted
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AskMessage(
    val text: String,
    val fromUser: Boolean,
    val citation: String? = null,
    val id: Long
)

data class AskState(
    val messages: List<AskMessage> = emptyList(),
    val thinking: Boolean = false,
    val modelReady: Boolean = false,
    val usingVectorSearch: Boolean = false
)

class AskViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(AskState())
    val state: StateFlow<AskState> = _state.asStateFlow()

    private var nextId = 0L
    private var job: Job? = null

    init {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            modelReady = GemmaEngine.isModelAvailable(context),
            usingVectorSearch = EmbeddingEngine.isAvailable(context)
        )
    }

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || _state.value.thinking) return

        val answerId = nextId++
        _state.value = _state.value.copy(
            messages = _state.value.messages +
                AskMessage(q, fromUser = true, id = nextId++) +
                AskMessage("", fromUser = false, id = answerId),
            thinking = true
        )

        job = viewModelScope.launch {
            val context = getApplication<Application>()
            val hits = MemorySearch.search(context, q)
            val prompt = AskPrompt.build(q, hits)

            val builder = StringBuilder()
            val ok = GemmaEngine.streamInto(context, prompt) { delta ->
                builder.append(delta)
                // Streamed into the bubble as it arrives. At ~15 tok/s a
                // three-sentence answer takes several seconds, and watching it
                // form is the difference between "working" and "frozen".
                update(answerId) { it.copy(text = builder.toString()) }
            }

            update(answerId) {
                it.copy(
                    text = if (ok && builder.isNotBlank()) builder.toString()
                    else "The model isn't loaded, so I can't answer that yet.",
                    // Only cited when the answer actually came from memories.
                    citation = if (ok && hits.isNotEmpty()) AskPrompt.citation(hits) else null
                )
            }
            _state.value = _state.value.copy(thinking = false)
        }
    }

    private fun update(id: Long, transform: (AskMessage) -> AskMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { if (it.id == id) transform(it) else it }
        )
    }

    fun stop() {
        job?.cancel()
        _state.value = _state.value.copy(thinking = false)
    }
}

@Composable
fun AskScreen(
    state: AskState,
    onAsk: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Header(state)

        if (state.messages.isEmpty()) {
            Empty(Modifier.weight(1f), onAsk)
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(state.messages, key = { it.id }) { message -> Bubble(message) }
            }
        }

        Composer(
            draft = draft,
            onDraft = { draft = it },
            enabled = !state.thinking,
            onSend = {
                onAsk(draft)
                draft = ""
            }
        )
    }
}

@Composable
private fun Header(state: AskState) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp)
    ) {
        Text("Ask your memory", style = MaterialTheme.typography.headlineSmall, color = Ink)
        Row(
            Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (state.modelReady) Green else InkSecondary))
            Text(
                if (state.modelReady) "GEMMA · ANSWERS OFFLINE" else "GEMMA · MODEL NOT INSTALLED",
                style = BadgeLabel,
                color = InkMuted
            )
        }
    }
}

@Composable
private fun Empty(modifier: Modifier, onAsk: (String) -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            "Everything you ask is answered here on the phone, from what DailyFlow has " +
                "already understood.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary
        )
        Column(
            Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Suggestions double as an explanation of what this can answer —
            // an empty chat box gives no clue what a "memory" even contains.
            listOf(
                "What's still open?",
                "What did I promise this week?",
                "Any bills due?"
            ).forEach { suggestion ->
                SecondaryChip(suggestion, { onAsk(suggestion) })
            }
        }
    }
}

@Composable
private fun Bubble(message: AskMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(
                    if (message.fromUser)
                        RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
                    else
                        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                )
                .background(if (message.fromUser) Violet else CardWhite)
                .then(
                    if (message.fromUser) Modifier
                    else Modifier.border(
                        1.dp,
                        BorderSoft,
                        RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                    )
                )
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            if (!message.fromUser) {
                Text("DAILYFLOW", style = BadgeLabel, color = InkMuted)
            }
            Text(
                // An empty answer bubble means the stream has not produced a
                // token yet; the ellipsis reads as thinking rather than broken.
                message.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (message.fromUser) Color.White else Ink,
                modifier = Modifier.padding(top = if (message.fromUser) 0.dp else 6.dp)
            )
            message.citation?.let {
                Text(
                    it.uppercase(),
                    style = BadgeLabel,
                    color = InkMuted,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Paper)
            // The nav bar floats over every screen, so the composer has to
            // clear it explicitly. Without this the whole input row sits
            // underneath the tab bar and looks like it is simply missing.
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 96.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextField(
                value = draft,
                onValueChange = onDraft,
                modifier = Modifier.weight(1f).clip(MaterialTheme.shapes.medium),
                placeholder = { Text("Ask about your day…") },
                enabled = enabled,
                maxLines = 3,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = CardWhite,
                    unfocusedContainerColor = CardWhite,
                    disabledContainerColor = PaperSunken,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() })
            )
            Box(
                Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (enabled && draft.isNotBlank()) Ink else PaperSunken)
                    .then(
                        if (enabled && draft.isNotBlank()) Modifier.flowClickable(onSend)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↑",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled && draft.isNotBlank()) Paper else InkSecondary
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(Green))
            Text(
                "  ANSWERED ON THIS DEVICE",
                style = BadgeLabel,
                color = InkMuted
            )
        }
    }
}
