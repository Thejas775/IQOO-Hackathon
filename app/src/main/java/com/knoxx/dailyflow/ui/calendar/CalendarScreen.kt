package com.knoxx.dailyflow.ui.calendar

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.knoxx.dailyflow.ui.CategoryTag
import com.knoxx.dailyflow.ui.DateLabels
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PromiseColors
import com.knoxx.dailyflow.ui.SourceBadge
import com.knoxx.dailyflow.ui.categoryColorsFor
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.BorderSoft
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.DaySheetShape
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkDisabled
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletDeep
import com.knoxx.dailyflow.ui.theme.VioletTint

@Composable
fun CalendarScreen(
    state: CalendarState,
    onSelectDay: (Long) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onAddTask: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        MonthHeader(state, onPreviousMonth, onNextMonth)
        WeekdayHeader()
        MonthGrid(state, onSelectDay)
        Legend()
        DaySheet(state, onAddTask, Modifier.weight(1f).padding(top = 16.dp))
    }
}

@Composable
private fun MonthHeader(state: CalendarState, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(state.year, style = Eyebrow, color = InkSecondary)
            Text(
                state.monthLabel,
                style = MaterialTheme.typography.headlineMedium,
                color = Ink,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ArrowButton("‹", onPrev)
            ArrowButton("›", onNext)
        }
    }
}

@Composable
private fun ArrowButton(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(MaterialTheme.shapes.medium).background(PaperSunken)
            .border(1.dp, BorderStrong, MaterialTheme.shapes.medium)
            .flowClickable(onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, style = MaterialTheme.typography.titleMedium, color = Ink)
    }
}

@Composable
private fun WeekdayHeader() {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
        // Monday-first, matching the grid's leading-blank calculation.
        listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
            Text(
                day,
                style = MonoFigure,
                color = InkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun MonthGrid(state: CalendarState, onSelectDay: (Long) -> Unit) {
    // Laid out as rows of seven rather than a LazyVerticalGrid: the whole
    // month is at most 42 cells and always visible, so lazy layout would add
    // nesting-scroll complications for nothing.
    val slots: List<DayCell?> = List(state.leadingBlanks) { null } + state.cells
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        slots.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                week.forEach { cell ->
                    Box(Modifier.weight(1f)) {
                        if (cell != null) {
                            DayCellView(cell, cell.timestamp == dayKey(state.selectedDay), onSelectDay)
                        } else {
                            Box(Modifier.height(46.dp))
                        }
                    }
                }
                // Pad a short final week so its cells keep the same width as
                // every other row rather than stretching to fill.
                repeat(7 - week.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

/** Days are compared at their own midnight, so selection survives the
 *  selected timestamp being any moment within that day. */
private fun dayKey(millis: Long): Long = java.util.Calendar.getInstance().apply {
    timeInMillis = millis
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
private fun DayCellView(cell: DayCell, selected: Boolean, onSelect: (Long) -> Unit) {
    Column(
        Modifier
            .height(46.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(
                when {
                    selected -> Ink
                    cell.isToday -> VioletTint
                    else -> Color.Transparent
                }
            )
            .flowClickable { onSelect(cell.timestamp) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            cell.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                selected -> Paper
                cell.isToday -> VioletDeep
                else -> Ink
            },
            fontWeight = FontWeight(if (selected || cell.isToday) 800 else 600)
        )
        Row(
            Modifier.height(5.dp).padding(top = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            cell.dotCategories.forEach { category ->
                Box(
                    Modifier.size(4.dp).clip(CircleShape).background(
                        // On the selected (dark) cell the category colours lose
                        // contrast, so dots go white there rather than
                        // disappearing into the ink background.
                        if (selected) Color.White.copy(alpha = 0.85f)
                        else categoryColorsFor(category).dot
                    )
                )
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LegendDot("Work", categoryColorsFor("Work").dot)
        LegendDot("Personal", categoryColorsFor("Personal").dot)
        LegendDot("Promise", PromiseColors.dot)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = InkSecondary, fontWeight = FontWeight(600))
    }
}

/**
 * The day sheet. In the design it is a raised panel anchored to the bottom of
 * the screen; here it is the lower half of the column with the same rounded
 * top and light ground, which keeps it scrollable without a nested drag
 * gesture fighting the month grid above it.
 */
@Composable
private fun DaySheet(state: CalendarState, onAddTask: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(DaySheetShape)
            .background(CardWhite)
            .border(1.dp, BorderSoft, DaySheetShape)
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 42.dp, height = 4.dp).clip(RoundedCornerShape(3.dp)).background(BorderStrong))
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column {
                Text(
                    DateLabels.dayTitle(state.selectedDay),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Ink
                )
                Text(
                    summaryLine(state),
                    style = Eyebrow,
                    color = InkSecondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            com.knoxx.dailyflow.ui.PrimaryChip("+ Task", onAddTask)
        }

        if (state.dayItems.isEmpty()) {
            EmptyDay(Modifier.padding(20.dp))
        } else {
            LazyColumn(
                Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.dayItems, key = { itemKey(it) }) { item -> DayItemRow(item) }
            }
        }
    }
}

/** Memories and device events can collide on id, so keys are namespaced. */
private fun itemKey(item: DayItem): String = when (item) {
    is DayItem.Memory -> "m${item.entity.id}"
    is DayItem.Event -> "e${item.event.id}"
}

private fun summaryLine(state: CalendarState): String {
    if (state.dayItems.isEmpty()) return "Nothing scheduled"
    val open = state.dayItems.count { it is DayItem.Memory && !it.entity.commitmentDone }
    val n = state.dayItems.size
    return "$n ${if (n == 1) "item" else "items"} · $open open"
}

@Composable
private fun DayItemRow(item: DayItem) {
    FlowCard(Modifier.fillMaxWidth(), background = Paper) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            val done = (item as? DayItem.Memory)?.entity?.commitmentDone == true
            Box(
                Modifier.size(21.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (done) Violet else CardWhite)
                    .then(if (done) Modifier else Modifier.border(2.dp, InkDisabled, RoundedCornerShape(7.dp)))
            )
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        when {
                            item is DayItem.Event && item.event.allDay -> "All day"
                            else -> DateLabels.time(item.startAt)
                        },
                        style = MonoFigure,
                        color = InkFaint
                    )
                }
                Row(
                    Modifier.padding(top = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (item) {
                        is DayItem.Memory -> {
                            CategoryTag(item.entity.category)
                            item.entity.personName?.let {
                                Text(
                                    "From $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary,
                                    fontWeight = FontWeight(600)
                                )
                            }
                            SourceBadge(item.entity.packageName.ifBlank { null })
                        }
                        // Device events are labelled as such so it is always
                        // clear which rows DailyFlow understood and which it
                        // merely read from the system calendar.
                        is DayItem.Event -> SourceBadge("com.google.android.calendar")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyDay(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .border(1.dp, InkDisabled, MaterialTheme.shapes.large)
            .padding(vertical = 26.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Nothing captured yet", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(
            "Items appear here as they show up in your apps.",
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary,
            textAlign = TextAlign.Center
        )
    }
}
