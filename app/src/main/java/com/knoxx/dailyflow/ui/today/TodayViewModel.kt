package com.knoxx.dailyflow.ui.today

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ui.DateLabels
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
)

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
}
