package com.knoxx.dailyflow.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.llm.BriefGenerator
import com.knoxx.dailyflow.ui.DateLabels
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The three tiles across the top. */
data class DayCounts(
    val yesterday: Int = 0,
    val today: Int = 0,
    val tomorrow: Int = 0,
    val keptToday: Int = 0
)

data class TodayState(
    val greeting: String,
    val dateLabel: String,
    val counts: DayCounts = DayCounts(),
    val openCommitments: List<MemoryEntity> = emptyList(),
    val timeline: List<MemoryEntity> = emptyList(),
    val people: List<String> = emptyList(),
    val memoryCount: Int = 0,
    val pendingCount: Int = 0
) {
    /** What "Play brief" reads aloud when no generated brief exists yet.
     *  Kept in sync with the card's own fallback so the spoken and written
     *  versions never disagree. */
    fun spokenFallback(): String = when {
        openCommitments.isEmpty() && counts.today == 0 ->
            "Nothing is due today. Your memory is clear."
        openCommitments.size == 1 -> "One promise is still open today."
        openCommitments.isEmpty() -> "${counts.today} on the calendar today, and nothing outstanding."
        else -> "${openCommitments.size} promises are still open today."
    }
}

class TodayViewModel(app: Application) : AndroidViewModel(app) {

    // Computed once, and shared by every query on the screen. Recomputing
    // "today" inside each Flow would let the counters and the list disagree
    // about where the day starts if they were evaluated either side of
    // midnight.
    private val today = MemoryRepository.dayBounds(0)
    private val yesterday = MemoryRepository.dayBounds(-1)
    private val tomorrow = MemoryRepository.dayBounds(1)

    private val counts = combine(
        MemoryRepository.observeCountDueBetween(yesterday.first, yesterday.second),
        MemoryRepository.observeCountDueBetween(today.first, today.second),
        MemoryRepository.observeCountDueBetween(tomorrow.first, tomorrow.second),
        MemoryRepository.observeKeptBetween(today.first, today.second),
        ::DayCounts
    )

    private val lists = combine(
        MemoryRepository.observeOpenCommitments(),
        MemoryRepository.observeDueBetween(today.first, today.second),
        MemoryRepository.observeDistinctPeople()
    ) { open, timeline, people -> Triple(open, timeline, people) }

    private val totals = combine(
        MemoryRepository.observeMemoryCount(),
        MemoryRepository.observePendingCount()
    ) { memories, pending -> memories to pending }

    /**
     * Grouped into three typed combines rather than one nine-flow call. The
     * vararg form of [combine] collapses every source to a common type, which
     * would mean casting each value back out by index — silently wrong the
     * moment a flow is reordered.
     */
    val state: StateFlow<TodayState> = combine(counts, lists, totals) { c, l, t ->
        TodayState(
            greeting = DateLabels.greeting(),
            dateLabel = DateLabels.eyebrow(),
            counts = c,
            openCommitments = l.first,
            timeline = l.second,
            people = l.third,
            memoryCount = t.first,
            pendingCount = t.second
        )
    }.stateIn(
        scope = viewModelScope,
        // Held briefly past the last collector so switching tabs and back does
        // not tear down and re-run nine queries.
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = TodayState(DateLabels.greeting(), DateLabels.eyebrow())
    )

    fun toggleDone(memory: MemoryEntity) {
        viewModelScope.launch {
            MemoryRepository.setDone(memory.id, !memory.commitmentDone)
        }
    }

    // ── generated text ───────────────────────────────────────────────────

    private val _brief = MutableStateFlow<String?>(null)
    val brief: StateFlow<String?> = _brief.asStateFlow()

    private val _draft = MutableStateFlow<DraftState?>(null)
    val draft: StateFlow<DraftState?> = _draft.asStateFlow()

    private var briefJob: Job? = null

    /**
     * Generated once per app open, not on every recomposition, and only when
     * there is something to summarise. It costs a full inference and blocks
     * the single engine lock, so regenerating it while the user is reading
     * would make the rest of the app feel stalled for no benefit.
     */
    fun generateBriefIfNeeded(todays: List<MemoryEntity>) {
        if (_brief.value != null || briefJob?.isActive == true || todays.isEmpty()) return

        // Only when the engine is *already* loaded. Opening Today must not
        // trigger a multi-gigabyte model load: it would cost ten seconds of
        // blank screen and push the process to the top of the kill list before
        // the user has asked for anything. The card falls back to a line
        // derived from the counts until something else — a sync, or Ask —
        // has loaded the model, at which point the brief appears for free.
        if (GemmaEngine.state.value != GemmaEngine.State.Ready) return

        briefJob = viewModelScope.launch {
            _brief.value = BriefGenerator.morningBrief(getApplication(), todays)
        }
    }

    fun draftFollowUp(memory: MemoryEntity) {
        if (_draft.value?.generating == true) return
        _draft.value = DraftState(memory = memory, generating = true)
        viewModelScope.launch {
            val text = BriefGenerator.draftFollowUp(getApplication(), memory)
            _draft.value = DraftState(
                memory = memory,
                generating = false,
                // Null means the model was unavailable or produced nothing.
                // Said plainly rather than shown as an empty sheet.
                text = text ?: "Couldn't draft that offline just now."
            )
        }
    }

    fun dismissDraft() {
        _draft.value = null
    }
}

data class DraftState(
    val memory: MemoryEntity,
    val generating: Boolean,
    val text: String = ""
)
