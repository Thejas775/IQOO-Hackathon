package com.knoxx.dailyflow.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knoxx.dailyflow.capture.CapturePermission
import com.knoxx.dailyflow.ui.calendar.CalendarScreen
import com.knoxx.dailyflow.ui.calendar.CalendarViewModel
import com.knoxx.dailyflow.ui.person.PersonScreen
import com.knoxx.dailyflow.ui.person.PersonViewModel
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.today.TodayScreen
import com.knoxx.dailyflow.ui.today.TodayViewModel
import com.knoxx.dailyflow.ui.you.YouScreen
import com.knoxx.dailyflow.ui.you.YouViewModel

/**
 * The app shell: four tabs, a raised add button, and a person detail that
 * opens over whichever tab is showing.
 *
 * Navigation is a single piece of state rather than a nav library. There are
 * four destinations and one detail screen; a graph, routes and argument
 * encoding would be more machinery than the whole surface justifies.
 */
@Composable
fun DailyFlowApp() {
    var tab by remember { mutableStateOf(Tab.Today) }
    var openPerson by remember { mutableStateOf<String?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Paper)) {

        AnimatedContent(
            targetState = openPerson ?: tab,
            transitionSpec = {
                // A plain cross-fade. The design's screens are dense and
                // full-width; sliding them would read as heavier than it is.
                fadeIn(tween(200)) togetherWith fadeOut(tween(160))
            },
            label = "screen"
        ) { target ->
            when (target) {
                is String -> {
                    val vm: PersonViewModel = viewModel()
                    // Keyed on the name so reopening a different person
                    // reloads rather than showing the previous one's data
                    // while the new query is still in flight.
                    LaunchedEffect(target) { vm.load(target) }
                    val personState by vm.state.collectAsState()
                    PersonScreen(
                        state = personState,
                        onBack = { openPerson = null },
                        onToggleDone = vm::toggleDone
                    )
                }

                Tab.Today -> {
                    val vm: TodayViewModel = viewModel()
                    val state by vm.state.collectAsState()
                    TodayScreen(
                        state = state,
                        onToggleDone = vm::toggleDone,
                        onOpenPerson = { openPerson = it },
                        onAddTask = { showAdd = true },
                        onOpenAsk = { tab = Tab.Ask },
                        onManageMemory = { tab = Tab.You }
                    )
                }

                Tab.Calendar -> {
                    val vm: CalendarViewModel = viewModel()
                    val calendarState by vm.state.collectAsState()
                    CalendarScreen(
                        state = calendarState,
                        onSelectDay = vm::selectDay,
                        onPreviousMonth = vm::previousMonth,
                        onNextMonth = vm::nextMonth,
                        onAddTask = { showAdd = true }
                    )
                }

                Tab.Ask -> ComingSoon(
                    "Ask",
                    "Ask your memory anything. Answered on this device, with the source it came from."
                )

                Tab.You -> {
                    val vm: YouViewModel = viewModel()
                    val youState by vm.state.collectAsState()
                    val context = LocalContext.current
                    YouScreen(
                        state = youState,
                        onTogglePause = { vm.setPaused(!youState.paused) },
                        // There is no API to request the accessibility
                        // permission — it can only be granted by hand, so this
                        // opens Settings rather than showing a dialog.
                        onEnableCapture = { context.startActivity(CapturePermission.settingsIntent()) },
                        onManageApps = { },
                        onOpenMemories = { },
                        onErase = vm::eraseEverything
                    )
                }

                else -> Unit
            }
        }

        // The person detail owns the whole screen, so the tab bar is hidden
        // while it is open — showing it would suggest the detail is a fifth
        // tab, and tapping the current tab would appear to do nothing.
        if (openPerson == null) {
            BottomNav(
                current = tab,
                onSelect = { tab = it },
                onAdd = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    if (showAdd) {
        AddTaskSheet(scope = scope, onDismiss = { showAdd = false })
    }
}

/** Stand-in until each screen lands. Says what will be there rather than
 *  showing a spinner, so a half-built app still reads as deliberate. */
@Composable
private fun ComingSoon(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
