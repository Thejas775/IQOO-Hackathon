package com.knoxx.dailyflow.ui.apps

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import com.knoxx.dailyflow.data.CapturePreferences
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.SectionHeader
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.sourceAppFor
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkFaint
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.Paper
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

/** One row: an app, how much it has produced, and whether it may be read. */
data class AppAccess(
    val packageName: String,
    val label: String,
    val captureCount: Int,
    val allowed: Boolean
)

class AllowListViewModel(app: Application) : AndroidViewModel(app) {

    /**
     * Apps that have produced captures, plus every app currently blocked.
     *
     * The union matters: an app blocked before it ever captured anything has
     * no rows, so a list built from captures alone would make it impossible to
     * find and re-enable — the setting would be one-way.
     */
    val state: StateFlow<List<AppAccess>> = combine(
        MemoryRepository.observePackageCounts(),
        CapturePreferences.excluded
    ) { counts, excluded ->
        val seen = counts.associate { it.packageName to it.captureCount }
        (seen.keys + excluded)
            .map { pkg ->
                AppAccess(
                    packageName = pkg,
                    label = sourceAppFor(pkg).label,
                    captureCount = seen[pkg] ?: 0,
                    allowed = pkg !in excluded
                )
            }
            // Noisiest first: the apps worth a decision are the ones filling
            // the queue, and they should not be below a quiet blocked one.
            .sortedWith(compareByDescending<AppAccess> { it.captureCount }.thenBy { it.label })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setAllowed(packageName: String, allowed: Boolean) =
        CapturePreferences.setAllowed(packageName, allowed)
}

@Composable
fun AllowListScreen(
    apps: List<AppAccess>,
    onBack: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
                    Text("‹", style = MaterialTheme.typography.titleLarge, color = Ink)
                }
                Text("App access", style = MaterialTheme.typography.headlineSmall, color = Ink)
            }
        }

        item {
            Text(
                "DailyFlow only reads the apps switched on here. Payment apps and " +
                    "home screens are off by default — the first is none of its business, " +
                    "the second has nothing worth remembering.",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
            )
        }

        if (apps.isEmpty()) {
            item {
                Text(
                    "Nothing captured yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkFaint,
                    modifier = Modifier.padding(20.dp)
                )
            }
        } else {
            item { SectionHeader("Apps", Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp)) }
            items(apps, key = { it.packageName }) { app ->
                AppRow(app, onToggle)
            }
        }
    }
}

@Composable
private fun AppRow(app: AppAccess, onToggle: (String, Boolean) -> Unit) {
    FlowCard(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    // The package name is shown because two apps can share a
                    // friendly label, and blocking the wrong one silently is
                    // exactly the mistake this screen must not invite.
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint
                )
            }
            Text(
                if (app.captureCount == 0) "—" else "${app.captureCount}",
                style = MonoFigure,
                color = InkSecondary
            )
            Switch(
                checked = app.allowed,
                onCheckedChange = { onToggle(app.packageName, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = Violet)
            )
        }
    }
}
