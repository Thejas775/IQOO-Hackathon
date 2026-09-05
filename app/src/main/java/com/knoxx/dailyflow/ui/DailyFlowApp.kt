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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.today.TodayScreen
import com.knoxx.dailyflow.ui.today.TodayViewModel

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
                is String -> PersonPlaceholder(target) { openPerson = null }

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

                Tab.Calendar -> ComingSoon(
                    "Calendar",
                    "Your month, with every captured commitment on the day it lands."
                )

                Tab.Ask -> ComingSoon(
                    "Ask",
                    "Ask your memory anything. Answered on this device, with the source it came from."
                )

                Tab.You -> ComingSoon(
                    "You",
                    "Capture status, which apps DailyFlow may read, and one tap to erase everything."
                )

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

@Composable
private fun PersonPlaceholder(name: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PersonAvatar(name, size = 62.dp)
        Text(
            name,
            style = MaterialTheme.typography.headlineMedium,
            color = Ink,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            "Everything about $name stays on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )
        SecondaryChip("Back", onBack, Modifier.padding(top = 20.dp))
    }
}
