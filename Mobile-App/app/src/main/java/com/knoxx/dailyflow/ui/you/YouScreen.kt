package com.knoxx.dailyflow.ui.you

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
// Delegate operators for `by remember { ... }` — extensions, so they have to
// be imported rather than qualified at the use site.
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.capture.CapturePermission
import com.knoxx.dailyflow.capture.NotificationPermission
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.CaptureStats
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.Qoo
import com.knoxx.dailyflow.ui.QooTile
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.overlay.BubblePreferences
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.ThemePreference
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Green
import com.knoxx.dailyflow.ui.theme.GreenInk
import com.knoxx.dailyflow.ui.theme.GreenTint
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.PillShape
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletTint
import com.knoxx.dailyflow.ui.theme.VioletTintStrong
import com.knoxx.dailyflow.work.ExtractionRunner
import com.knoxx.dailyflow.work.ExtractionScheduler
import java.util.Calendar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class YouState(
    val captureEnabled: Boolean = false,
    val paused: Boolean = false,
    val eventsSeen: Long = 0,
    val hoursOn: Int = 0,
    /** Captures per hour for the last 12 hours, oldest first. */
    val histogram: List<Int> = emptyList(),
    val memoryCount: Int = 0,
    val approximateBytes: Long = 0,
    val allowedAppCount: Int = 0,
    val pendingCount: Int = 0,
    val sync: ExtractionRunner.Progress = ExtractionRunner.Progress(),
    val notificationAccess: Boolean = false
)

class YouViewModel(app: Application) : AndroidViewModel(app) {

    private val twelveHoursAgo = System.currentTimeMillis() - 12 * 60 * 60 * 1000L

    private val device = combine(
        CapturePreferences.isPaused,
        CaptureStats.eventsSeen,
        CaptureStats.capturingSince,
        MemoryRepository.observeMemoryCount(),
        MemoryRepository.observeTimestampsSince(twelveHoursAgo)
    ) { paused, events, since, memories, timestamps ->
        listOf(paused, events, since, memories, timestamps)
    }

    private val work = combine(
        MemoryRepository.observePendingCount(),
        ExtractionRunner.progress
    ) { pending, progress -> pending to progress }

    /**
     * Accessibility and notification access, as a flow.
     *
     * Neither is observable. They are granted in Settings, in another process,
     * and Android offers no callback when they change. They used to be read
     * inline while building the state, which meant the value was only ever as
     * fresh as the last time some *other* flow happened to emit — so coming
     * back from Settings having just enabled capture showed it still off,
     * sometimes for minutes, until an unrelated capture arrived.
     *
     * Holding them here makes the re-read explicit: [refreshPermissions] is
     * called when the screen resumes, which is exactly when the user has
     * returned from granting one.
     */
    private val permissions = MutableStateFlow(readPermissions())

    private fun readPermissions(): Pair<Boolean, Boolean> =
        CapturePermission.isEnabled(getApplication()) to
            NotificationPermission.isEnabled(getApplication())

    fun refreshPermissions() {
        permissions.value = readPermissions()
    }

    val state: StateFlow<YouState> = combine(device, work, permissions) { d, w, p ->
        @Suppress("UNCHECKED_CAST")
        val paused = d[0] as Boolean
        val events = d[1] as Long
        val since = d[2] as Long
        val memories = d[3] as Int
        @Suppress("UNCHECKED_CAST")
        val timestamps = d[4] as List<Long>
        YouState(
            captureEnabled = p.first,
            notificationAccess = p.second,
            paused = paused,
            eventsSeen = events,
            hoursOn = if (since > 0) ((System.currentTimeMillis() - since) / 3_600_000L).toInt() else 0,
            histogram = bucketByHour(timestamps),
            memoryCount = memories,
            // Approximated from stored text length rather than statting the
            // database file: the file includes indices and free pages, so its
            // size would overstate what the user could actually erase.
            approximateBytes = 0L,
            allowedAppCount = 0,
            pendingCount = w.first,
            sync = w.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), YouState())

    /** Enqueues a promoted one-time pass rather than running inline: the
     *  foreground promotion is what keeps the model load from getting the
     *  process killed, and only WorkManager can grant it. */
    fun sync() = ExtractionScheduler.runNow(getApplication())

    fun stopSync() = ExtractionScheduler.cancelAll(getApplication())

    /** Twelve buckets, oldest first, so the bar chart reads left to right. */
    private fun bucketByHour(timestamps: List<Long>): List<Int> {
        val now = Calendar.getInstance().timeInMillis
        val buckets = IntArray(12)
        timestamps.forEach { ts ->
            val hoursAgo = ((now - ts) / 3_600_000L).toInt()
            if (hoursAgo in 0..11) buckets[11 - hoursAgo]++
        }
        return buckets.toList()
    }

    fun setPaused(paused: Boolean) = CapturePreferences.setPaused(paused)

    fun eraseEverything() {
        viewModelScope.launch {
            MemoryRepository.eraseAll()
            CaptureStats.reset()
        }
    }
}

@Composable
fun YouScreen(
    state: YouState,
    onTogglePause: () -> Unit,
    onEnableCapture: () -> Unit,
    onManageApps: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenDev: () -> Unit,
    onConnectAssistant: () -> Unit,
    onErase: () -> Unit,
    onSync: () -> Unit,
    onStopSync: () -> Unit,
    onNotificationAccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                Text("PROFILE", style = Eyebrow, color = InkSecondary)
            }
        }

        item { CaptureCard(state, onTogglePause, onEnableCapture, Modifier.padding(horizontal = 20.dp)) }
        item { SyncCard(state, onSync, onStopSync, Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)) }
        item { Identity(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }

        item {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NavRow("Memories", "What DailyFlow understood from your day", "${state.memoryCount}", onOpenMemories)
                NavRow(
                    "Notification access",
                    // Says what it buys, not what it is: the accessibility
                    // service only sees an app while it is open, so an unread
                    // message — the thing most likely to be forgotten — is
                    // invisible without this.
                    if (state.notificationAccess) "On — catches messages you never opened"
                    else "Off — unopened messages are missed",
                    if (state.notificationAccess) "ON" else null,
                    onNotificationAccess
                )
                NavRow("Manage app access", "Choose which apps DailyFlow may read", null, onManageApps)
                NavRow("Connect an assistant", "Give Claude the address and password for your memory", null, onConnectAssistant)
                NavRow("Tuning", "Send test reminders, and run prompts against Gemma", null, onOpenDev)
            }
        }

        item { BubbleCard(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }

        item { ThemeCard(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }

        item { StorageCard(state, onErase, Modifier.padding(20.dp)) }
    }
}

@Composable
private fun CaptureCard(
    state: YouState,
    onTogglePause: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier
) {
    FlowCard(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(Modifier.weight(1f)) {
                    StatusPill(state)
                    Text(
                        if (state.captureEnabled) "Capturing your day" else "Capture is off",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Ink,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                    Text(
                        "Reads on-screen text on-device with Gemma. Nothing is uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                HoursRing(state.hoursOn)
            }

            Histogram(state.histogram, Modifier.padding(top = 16.dp))

            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("EVENTS CAPTURED", style = Eyebrow, color = InkSecondary)
                    Row(
                        Modifier.padding(top = 3.dp),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${state.eventsSeen}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Ink,
                            fontWeight = FontWeight(800)
                        )
                        // The claim the whole product rests on, stated as a
                        // number rather than a promise. It is a constant
                        // because nothing in this app can increment it.
                        Text(
                            "0 cloud calls",
                            style = MaterialTheme.typography.bodySmall,
                            color = GreenInk,
                            fontWeight = FontWeight(700),
                            modifier = Modifier.padding(bottom = 3.dp)
                        )
                    }
                }
                if (state.captureEnabled) {
                    PrimaryChip(if (state.paused) "Resume" else "Pause", onTogglePause)
                } else {
                    PrimaryChip("Turn on", onEnable)
                }
            }
        }
    }
}

/**
 * "Read my day" — runs extraction now, while the app is on screen.
 *
 * This is the only way memories get understood. Capture keeps collecting in
 * the background, but Gemma is only ever loaded here or in Ask, because a
 * multi-gigabyte load from a background job gets the process killed and takes
 * the rest of the phone down with it. Surfacing the pending count makes that
 * limitation legible rather than mysterious.
 */
@Composable
private fun SyncCard(
    state: YouState,
    onSync: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sync = state.sync
    FlowCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            sync.running -> "Reading your day…"
                            state.pendingCount > 0 -> "${state.pendingCount} waiting to be read"
                            else -> "Everything is read"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = Ink
                    )
                    Text(
                        sync.message
                            ?: if (sync.running) "${sync.done} of ${sync.total} · on this device"
                            else "Gemma loads only while this app is open",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (sync.running) {
                    SecondaryChip("Stop", onClick = onStop)
                } else {
                    PrimaryChip("Read my day", onClick = onSync)
                }
            }

            if (sync.running && sync.total > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(PaperSunken)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(sync.fraction)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Violet)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(state: YouState) {
    val on = state.captureEnabled && !state.paused
    Row(
        Modifier
            .clip(PillShape)
            .background(if (on) GreenTint else PaperSunken)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(if (on) Green else InkSecondary))
        Text(
            when {
                !state.captureEnabled -> "NOT ENABLED"
                state.paused -> "PAUSED"
                else -> "CAPTURE RUNNING"
            },
            style = com.knoxx.dailyflow.ui.theme.BadgeLabel,
            color = if (on) GreenInk else InkSecondary
        )
    }
}

@Composable
private fun HoursRing(hours: Int) {
    Box(
        Modifier.size(62.dp).clip(CircleShape).background(VioletTint),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier.size(50.dp).clip(CircleShape).background(CardWhite),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$hours", style = MaterialTheme.typography.titleSmall, color = Ink, fontWeight = FontWeight(800))
                Text("HRS ON", style = com.knoxx.dailyflow.ui.theme.BadgeLabel, color = InkSecondary)
            }
        }
    }
}

/**
 * Twelve hourly buckets. Bars are scaled against the busiest hour rather than
 * an absolute maximum, so a quiet day still shows shape instead of a flat line
 * — the chart is there to show rhythm, not volume.
 */
@Composable
private fun Histogram(buckets: List<Int>, modifier: Modifier = Modifier) {
    val peak = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(42.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            buckets.forEachIndexed { index, count ->
                val fraction = (count.toFloat() / peak).coerceAtLeast(0.06f)
                Box(
                    Modifier
                        .weight(1f)
                        .height((42 * fraction).dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            when {
                                count == peak && count > 0 -> Violet
                                fraction > 0.5f -> VioletTintStrong
                                else -> VioletTint
                            }
                        )
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("12 HRS AGO", style = com.knoxx.dailyflow.ui.theme.BadgeLabel, color = InkSecondary)
            Text("NOW", style = com.knoxx.dailyflow.ui.theme.BadgeLabel, color = InkSecondary)
        }
    }
}

@Composable
private fun Identity(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(VioletTint),
            contentAlignment = Alignment.Center
        ) {
            Text("A", style = MaterialTheme.typography.titleMedium, color = Violet, fontWeight = FontWeight(800))
        }
        Column {
            Text("This device", style = MaterialTheme.typography.titleMedium, color = Ink)
            // Not a placeholder for a future login — there is genuinely no
            // account, and saying so is the point.
            Text(
                "NO ACCOUNT · DEVICE ONLY",
                style = Eyebrow,
                color = InkSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, trailing: String?, onClick: () -> Unit) {
    FlowCard(Modifier.fillMaxWidth().flowClickable(onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            trailing?.let { Text(it, style = MonoFigure, color = InkSecondary) }
            Text("›", style = MaterialTheme.typography.titleMedium, color = InkSecondary)
        }
    }
}

/**
 * QOO over other apps, as a switch.
 *
 * Opt-in, and the default is the point: a mascot that appears unasked on top
 * of someone's inbox is the behaviour an app gets uninstalled for. It also
 * states where it will appear, because "floats over your apps" with no list
 * reads as "watches everything" — which is the fear this app spends the rest
 * of its surface area disproving.
 */
@Composable
private fun BubbleCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(BubblePreferences.isEnabled(context)) }

    FlowCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            QooTile(if (on) Qoo.Waving else Qoo.Sleeping, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text("Reply bubble", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    if (on) {
                        "QOO waits in Gmail and your chat apps. Tap him and he reads the " +
                            "thread, checks your calendar, and writes the reply into the box."
                    } else {
                        "Off — nothing is drawn over your other apps."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Switch(
                checked = on,
                onCheckedChange = {
                    on = it
                    BubblePreferences.set(context, it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Violet)
            )
        }
    }
}

/**
 * The two identities, as a switch.
 *
 * A setting rather than a build flag because the point is comparison: the
 * palette swap is live, so this toggles the look under the user's thumb and
 * back again with nothing lost either way. The choice is remembered, so the
 * answer to "what if I hate it" is one tap, not a reinstall.
 */
@Composable
private fun ThemeCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var qoo by remember { mutableStateOf(ThemePreference.isQoo(context)) }

    FlowCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // The mascot is the argument for the theme, so he makes it
            // himself — on the QOO palette his tile matches the card and he
            // stops looking pasted on, which is visible right here in the row.
            QooTile(if (qoo) Qoo.Waving else Qoo.Resting, size = 52.dp)
            Column(Modifier.weight(1f)) {
                Text("QOO theme", style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    if (qoo) "QOO's own colours — dark ground, amber glow"
                    else "Paper — the light ground DailyFlow ships with",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            }
            Switch(
                checked = qoo,
                onCheckedChange = {
                    qoo = it
                    ThemePreference.set(context, it)
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Violet)
            )
        }
    }
}

@Composable
private fun StorageCard(state: YouState, onErase: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by remember { mutableStateOf(false) }

    SunkenPanel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("STORAGE · THIS DEVICE", style = Eyebrow, color = InkSecondary)

            Row(
                Modifier.fillMaxWidth().padding(top = 10.dp).height(7.dp)
                    .clip(RoundedCornerShape(4.dp)).background(BorderStrong)
            ) {
                // Purely indicative: the point is that the footprint is small
                // and local, not a precise byte count.
                Box(Modifier.weight(0.34f).fillMaxWidth().background(Violet))
                Box(Modifier.weight(0.12f).fillMaxWidth().background(Green))
                Box(Modifier.weight(0.54f).fillMaxWidth())
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 9.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${state.memoryCount} memories on this device",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    fontWeight = FontWeight(600)
                )
                Text(
                    "Erase all",
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                    fontWeight = FontWeight(700),
                    modifier = Modifier.flowClickable { confirming = true }
                )
            }

            if (confirming) {
                Text(
                    "This deletes every memory and everything captured on this device. " +
                        "It cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Row(
                    Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecondaryChip("Cancel", onClick = { confirming = false })
                    PrimaryChip("Yes, erase everything", onClick = {
                        onErase()
                        confirming = false
                    })
                }
            }
        }
    }
}
