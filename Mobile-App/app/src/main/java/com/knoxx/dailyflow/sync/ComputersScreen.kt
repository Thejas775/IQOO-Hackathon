package com.knoxx.dailyflow.sync

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ComputersState(
    val paired: List<PairedComputer> = emptyList(),
    val adding: Boolean = false,
    val searching: Boolean = false,
    val found: List<FoundComputer> = emptyList(),
    val chosen: FoundComputer? = null,
    val manualHost: String = "",
    val code: String = "",
    val busy: Boolean = false,
    val note: String? = null,
    val noteIsError: Boolean = false
)

class ComputersViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ComputersState(paired = PairedComputers.all(app)))
    val state: StateFlow<ComputersState> = _state.asStateFlow()

    private fun reload() {
        _state.value = _state.value.copy(paired = PairedComputers.all(getApplication()))
    }

    fun startAdding() {
        _state.value = _state.value.copy(adding = true, chosen = null, code = "", note = null)
        search()
    }

    fun cancelAdding() {
        _state.value = _state.value.copy(adding = false, chosen = null, code = "", found = emptyList())
    }

    fun search() {
        if (_state.value.searching) return
        _state.value = _state.value.copy(searching = true, note = null)
        viewModelScope.launch {
            val found = LanDiscovery.find(getApplication())
            _state.value = _state.value.copy(searching = false, found = found)
        }
    }

    fun choose(computer: FoundComputer) {
        _state.value = _state.value.copy(chosen = computer, code = "", note = null)
    }

    fun setManualHost(host: String) {
        _state.value = _state.value.copy(manualHost = host.trim())
    }

    /** For networks that drop broadcasts: the address the PC shows. */
    fun chooseManual() {
        val host = _state.value.manualHost
        if (host.isEmpty()) return
        choose(FoundComputer(id = "", name = host, host = host, port = SyncProtocol.SYNC_PORT, pairing = true))
    }

    fun setCode(code: String) {
        _state.value = _state.value.copy(code = code.filter { it.isDigit() }.take(6))
    }

    fun pair() {
        val s = _state.value
        val pc = s.chosen ?: return
        if (s.code.length != 6 || s.busy) return
        _state.value = s.copy(busy = true, note = null)
        viewModelScope.launch {
            val outcome = PcSync.pair(getApplication(), pc.host, pc.port, s.code)
            if (outcome.ok) {
                _state.value = _state.value.copy(
                    busy = false, adding = false, chosen = null, code = "",
                    note = "${outcome.message}. Syncing your memories now…", noteIsError = false
                )
                reload()
                val synced = PcSync.syncAll(getApplication()).lastOrNull()
                _state.value = _state.value.copy(
                    note = synced?.message ?: outcome.message,
                    noteIsError = synced?.ok == false
                )
                PcSyncScheduler.schedulePeriodic(getApplication())
            } else {
                _state.value = _state.value.copy(busy = false, note = outcome.message, noteIsError = true)
            }
            reload()
        }
    }

    fun syncNow(computer: PairedComputer) {
        _state.value = _state.value.copy(busy = true, note = "Syncing with ${computer.name}…", noteIsError = false)
        viewModelScope.launch {
            val outcome = PcSync.syncOne(getApplication(), computer.id)
            _state.value = _state.value.copy(busy = false, note = outcome.message, noteIsError = !outcome.ok)
            reload()
        }
    }

    /** Stops syncing with that PC. Memories already synced stay on both. */
    fun remove(computer: PairedComputer) {
        PairedComputers.remove(getApplication(), computer.id)
        _state.value = _state.value.copy(note = "Removed ${computer.name}. Memories already synced stay.")
        reload()
    }
}

/**
 * Pair this phone with DailyFlow on a computer, and keep both in step over
 * the local Wi-Fi. Reached from You → Add computer.
 */
@Composable
fun ComputersScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val vm: ComputersViewModel = viewModel()
    val state by vm.state.collectAsState()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Paper),
        contentPadding = PaddingValues(bottom = 40.dp)
    ) {
        item {
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
                Text("Computers", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }
        item {
            Text(
                "Your memories, tasks and calendar stay in step with DailyFlow on your computer. " +
                    "They travel straight between the two over your own Wi-Fi, encrypted — no " +
                    "account, no server. Only what DailyFlow understood is shared, never the raw " +
                    "screen text, and every card says which device saw it.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
        }

        state.note?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.noteIsError) Danger else Violet,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
                )
            }
        }

        item { SectionHeader("Paired", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }
        if (state.paired.isEmpty()) {
            item {
                Text(
                    "No computer yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                )
            }
        }
        state.paired.forEach { pc ->
            item(key = pc.id) {
                FlowCard(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(pc.name, style = MaterialTheme.typography.titleSmall, color = Ink)
                        Text(
                            pc.lastError ?: (if (pc.lastSyncAt > 0) "Last synced ${ago(pc.lastSyncAt)}"
                            else "Not synced yet"),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (pc.lastError != null) Danger else InkSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryChip("Sync now", onClick = { vm.syncNow(pc) })
                            SecondaryChip("Remove", onClick = { vm.remove(pc) })
                        }
                    }
                }
            }
        }

        item {
            if (!state.adding) {
                PrimaryChip(
                    "Add computer",
                    onClick = vm::startAdding,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)
                )
            }
        }

        if (state.adding) {
            item { SectionHeader("Add computer", Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp)) }
            item {
                SunkenPanel(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("1. Put the phone and the computer on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall, color = Ink)
                        Text("2. On the computer, open DailyFlow → Model & settings → Pair a phone.",
                            style = MaterialTheme.typography.bodySmall, color = Ink)
                        Text("3. Pick it below and type the 6-digit code it shows.",
                            style = MaterialTheme.typography.bodySmall, color = Ink)
                    }
                }
            }

            val chosen = state.chosen
            if (chosen == null) {
                item {
                    Text(
                        if (state.searching) "LOOKING ON THIS WI-FI…"
                        else if (state.found.isEmpty()) "NO COMPUTER FOUND" else "FOUND",
                        style = Eyebrow,
                        color = InkSecondary,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
                    )
                }
                state.found.forEach { found ->
                    item(key = "found-${found.id}") {
                        FlowCard(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                                .flowClickable { vm.choose(found) }
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(found.name, style = MaterialTheme.typography.titleSmall, color = Ink)
                                Text(
                                    if (found.pairing) "Showing a code — tap to pair"
                                    else "Press Pair a phone on it first, then tap here",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkSecondary
                                )
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SecondaryChip(if (state.searching) "Searching…" else "Search again", onClick = vm::search)
                        SecondaryChip("Cancel", onClick = vm::cancelAdding)
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(
                            "Not listed? Some Wi-Fi networks hide devices from each other. Type the " +
                                "address shown under Phones on the computer:",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSecondary
                        )
                        OutlinedTextField(
                            value = state.manualHost,
                            onValueChange = vm::setManualHost,
                            singleLine = true,
                            placeholder = { Text("192.168.1.20") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        )
                        SecondaryChip("Use this address", onClick = vm::chooseManual,
                            modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else {
                item {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Code shown on ${chosen.name}", style = MaterialTheme.typography.titleSmall, color = Ink)
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = vm::setCode,
                            singleLine = true,
                            placeholder = { Text("6 digits") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            textStyle = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryChip(if (state.busy) "Pairing…" else "Pair", onClick = vm::pair)
                            SecondaryChip("Back", onClick = vm::startAdding)
                        }
                    }
                }
            }
        }
    }
}

private fun ago(at: Long): String {
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}
