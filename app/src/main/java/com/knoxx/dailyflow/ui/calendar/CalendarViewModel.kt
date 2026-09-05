package com.knoxx.dailyflow.ui.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.data.CalendarEvent
import com.knoxx.dailyflow.data.DeviceCalendar
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One day in the grid: the number for the cell, plus the dots under it. */
data class DayCell(
    val dayOfMonth: Int,
    val timestamp: Long,
    val isToday: Boolean,
    val dotCategories: List<String?>,
    val hasPromise: Boolean
)

/** A row in the day sheet — either a captured memory or a device event. */
sealed interface DayItem {
    val startAt: Long
    val title: String

    data class Memory(val entity: MemoryEntity) : DayItem {
        override val startAt get() = entity.dueAt ?: entity.timestamp
        override val title get() = entity.summary.orEmpty()
    }

    data class Event(val event: CalendarEvent) : DayItem {
        override val startAt get() = event.startAt
        override val title get() = event.title
    }
}

data class CalendarState(
    val monthLabel: String = "",
    val year: String = "",
    val leadingBlanks: Int = 0,
    val cells: List<DayCell> = emptyList(),
    val selectedDay: Long = System.currentTimeMillis(),
    val dayItems: List<DayItem> = emptyList(),
    val calendarPermissionGranted: Boolean = false
)

class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CalendarState())
    val state: StateFlow<CalendarState> = _state.asStateFlow()

    private var monthAnchor = Calendar.getInstance()

    // Both queries are open-ended Flow collections. Without cancelling the
    // previous one, every month change and every day tap would leave another
    // live collector writing into the same state — the screen would keep
    // reverting to whichever stale query emitted last.
    private var monthJob: Job? = null
    private var dayJob: Job? = null

    init {
        loadMonth()
        selectDay(System.currentTimeMillis())
    }

    fun previousMonth() {
        monthAnchor = (monthAnchor.clone() as Calendar).apply { add(Calendar.MONTH, -1) }
        loadMonth()
    }

    fun nextMonth() {
        monthAnchor = (monthAnchor.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        loadMonth()
    }

    private fun loadMonth() {
        val start = (monthAnchor.clone() as Calendar).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.MONTH, 1) }
        val daysInMonth = start.getActualMaximum(Calendar.DAY_OF_MONTH)

        // Monday-first, matching the design's M T W T F S S header. Calendar's
        // DAY_OF_WEEK is Sunday=1, so this shifts it rather than assuming the
        // device's own first-day-of-week, which would misalign the header.
        val leading = (start.get(Calendar.DAY_OF_WEEK) + 5) % 7

        monthJob?.cancel()
        monthJob = viewModelScope.launch {
            MemoryRepository.observeMonth(start.timeInMillis, end.timeInMillis).collect { memories ->
                val byDay = memories.groupBy { dayOfMonthFor(it.dueAt ?: it.timestamp) }
                val todayDay = todayIfInMonth(start)

                _state.value = _state.value.copy(
                    monthLabel = com.knoxx.dailyflow.ui.DateLabels.month(start.timeInMillis),
                    year = start.get(Calendar.YEAR).toString(),
                    leadingBlanks = leading,
                    cells = (1..daysInMonth).map { day ->
                        val forDay = byDay[day].orEmpty()
                        DayCell(
                            dayOfMonth = day,
                            timestamp = (start.clone() as Calendar)
                                .apply { set(Calendar.DAY_OF_MONTH, day) }.timeInMillis,
                            isToday = day == todayDay,
                            // Capped at three: the design shows at most three
                            // dots, and a cell of ten would be noise.
                            dotCategories = forDay.take(3).map { it.category },
                            hasPromise = forDay.any {
                                it.directionOrNull?.isObligation == true
                            }
                        )
                    }
                )
            }
        }
    }

    fun selectDay(timestamp: Long) {
        val bounds = boundsFor(timestamp)
        _state.value = _state.value.copy(selectedDay = timestamp)

        dayJob?.cancel()
        dayJob = viewModelScope.launch {
            val context = getApplication<Application>()
            val events = DeviceCalendar.eventsBetween(context, bounds.first, bounds.second)
                .map(DayItem::Event)

            MemoryRepository.observeDueBetween(bounds.first, bounds.second).collect { memories ->
                // Merged and sorted together, so a captured commitment and a
                // real calendar event at the same hour sit side by side rather
                // than in two separate lists the user has to reconcile.
                val merged = (memories.map(DayItem::Memory) + events).sortedBy { it.startAt }
                _state.value = _state.value.copy(
                    dayItems = merged,
                    calendarPermissionGranted = DeviceCalendar.hasPermission(context)
                )
            }
        }
    }

    private fun boundsFor(timestamp: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }

    private fun dayOfMonthFor(millis: Long): Int =
        Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_MONTH)

    /** The today marker only applies when the grid is showing this month. */
    private fun todayIfInMonth(monthStart: Calendar): Int? {
        val now = Calendar.getInstance()
        return if (now.get(Calendar.YEAR) == monthStart.get(Calendar.YEAR) &&
            now.get(Calendar.MONTH) == monthStart.get(Calendar.MONTH)
        ) now.get(Calendar.DAY_OF_MONTH) else null
    }
}
