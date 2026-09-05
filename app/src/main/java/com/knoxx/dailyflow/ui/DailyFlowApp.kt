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
import androidx.compose.runtime.DisposableEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knoxx.dailyflow.capture.CapturePermission
import com.knoxx.dailyflow.capture.NotificationPermission
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.ui.apps.AllowListScreen
import com.knoxx.dailyflow.ui.apps.AllowListViewModel
import com.knoxx.dailyflow.ui.ask.AskScreen
import com.knoxx.dailyflow.ui.dev.DevScreen
import com.knoxx.dailyflow.ui.dev.DevViewModel
import com.knoxx.dailyflow.ui.memories.MemoriesScreen
import com.knoxx.dailyflow.ui.memories.MemoriesViewModel
import com.knoxx.dailyflow.ui.ask.AskViewModel
import com.knoxx.dailyflow.ui.calendar.CalendarScreen
import com.knoxx.dailyflow.ui.calendar.CalendarViewModel
import com.knoxx.dailyflow.ui.person.PersonScreen
import com.knoxx.dailyflow.ui.model.ModelSetupSheet
import com.knoxx.dailyflow.ui.model.ModelSetupViewModel
import com.knoxx.dailyflow.ui.person.PersonViewModel
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.today.DraftSheet
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
    var showModelSetup by remember { mutableStateOf(false) }
    var showAllowList by remember { mutableStateOf(false) }
    var showMemories by remember { mutableStateOf(false) }
    var showDev by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val modelVm: ModelSetupViewModel = viewModel()
    val modelState by modelVm.state.collectAsState()

    // Offered unprompted the first time the app opens without a model.
    // Without it the app is inert — capture still records screens, nothing is
    // ever understood, and the only clue is a permanently empty Today. Shown
    // once per launch rather than on every recomposition so it can be
    // dismissed and the app still used.
    var promptedForModel by remember { mutableStateOf(false) }
    LaunchedEffect(modelState.installed) {
        if (!modelState.installed && !promptedForModel) {
            promptedForModel = true
            showModelSetup = true
        }
    }

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
                    val brief by vm.brief.collectAsState()
                    val draft by vm.draft.collectAsState()
                    val speaker = rememberSpeaker()

                    // Keyed on the timeline so the brief is generated once the
                    // day's data has actually arrived, and regenerated if it
                    // changes — not on every recomposition, which would queue
                    // an inference behind every frame.
                    LaunchedEffect(state.timeline) { vm.generateBriefIfNeeded(state.timeline) }

                    TodayScreen(
                        state = state,
                        onToggleDone = vm::toggleDone,
                        onOpenPerson = { openPerson = it },
                        onAddTask = { showAdd = true },
                        onOpenAsk = { tab = Tab.Ask },
                        onManageMemory = { tab = Tab.You },
                        modelMissing = !modelState.installed,
                        onGetModel = { showModelSetup = true },
                        briefText = brief,
                        // Speaks whatever the card is showing, generated or
                        // fallback, so the button is never a dead control.
                        onPlayBrief = { speaker.speak(brief ?: state.spokenFallback()) },
                        onDraftFollowUp = vm::draftFollowUp
                    )

                    draft?.let { DraftSheet(it, onDismiss = vm::dismissDraft) }
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

                Tab.Ask -> {
                    val vm: AskViewModel = viewModel()
                    val askState by vm.state.collectAsState()
                    AskScreen(state = askState, onAsk = vm::ask)
                }

                Tab.You -> {
                    val vm: YouViewModel = viewModel()
                    val youState by vm.state.collectAsState()
                    val context = LocalContext.current

                    // Accessibility and notification access are granted in
                    // Settings, in another process, and nothing tells the app
                    // when that happens. Coming back here is the one moment we
                    // know to look again — without it the card kept saying
                    // capture was off after the user had just turned it on.
                    val lifecycleOwner = LocalLifecycleOwner.current
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) vm.refreshPermissions()
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }
                    YouScreen(
                        state = youState,
                        onTogglePause = { vm.setPaused(!youState.paused) },
                        // There is no API to request the accessibility
                        // permission — it can only be granted by hand, so this
                        // opens Settings rather than showing a dialog.
                        onEnableCapture = { context.startActivity(CapturePermission.settingsIntent()) },
                        onManageApps = { showAllowList = true },
                        onOpenMemories = { showMemories = true },
                        onOpenDev = { showDev = true },
                        onErase = vm::eraseEverything,
                        onSync = vm::sync,
                        onStopSync = vm::stopSync,
                        onNotificationAccess = {
                            context.startActivity(NotificationPermission.settingsIntent())
                        }
                    )
                }

                else -> Unit
            }
        }

        // The person detail owns the whole screen, so the tab bar is hidden
        // while it is open — showing it would suggest the detail is a fifth
        // tab, and tapping the current tab would appear to do nothing.
        if (openPerson == null && !showAllowList && !showMemories && !showDev) {
            BottomNav(
                current = tab,
                onSelect = { tab = it },
                onAdd = { showAdd = true },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // A full-screen overlay rather than a fifth tab: it is reached from a
        // row inside You, and showing the tab bar over it would suggest the
        // tabs still switch underneath.
        if (showAllowList) {
            val appsVm: AllowListViewModel = viewModel()
            val apps by appsVm.state.collectAsState()
            AllowListScreen(
                apps = apps,
                onBack = { showAllowList = false },
                onToggle = appsVm::setAllowed
            )
        }

        if (showMemories) {
            val memVm: MemoriesViewModel = viewModel()
            val memories by memVm.memories.collectAsState()
            MemoriesScreen(
                memories = memories,
                onBack = { showMemories = false }
            )
        }

        if (showDev) {
            val devVm: DevViewModel = viewModel()
            val devState by devVm.state.collectAsState()
            val ctx = LocalContext.current
            DevScreen(
                state = devState,
                tracePath = DebugLog.path(ctx),
                onBack = { showDev = false },
                onRun = devVm::run,
                onLoadReal = devVm::loadRealPrompt,
                onTestReminders = devVm::sendTestReminders,
                onBenchmark = devVm::runFormatBenchmark
            )
        }
    }

    if (showAdd) {
        AddTaskSheet(scope = scope, onDismiss = { showAdd = false })
    }

    if (showModelSetup) {
        ModelSetupSheet(
            state = modelState,
            onSelect = modelVm::select,
            onDownload = modelVm::download,
            onCancel = modelVm::cancel,
            onDiscardPartial = modelVm::discardPartial,
            onDismiss = { showModelSetup = false }
        )
    }
}
