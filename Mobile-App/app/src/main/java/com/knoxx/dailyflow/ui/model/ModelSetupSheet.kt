package com.knoxx.dailyflow.ui.model

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.llm.ModelCatalog
import com.knoxx.dailyflow.llm.ModelDownloader
import com.knoxx.dailyflow.llm.ModelLocator
import com.knoxx.dailyflow.llm.ModelSpec
import com.knoxx.dailyflow.ui.FlowCard
import com.knoxx.dailyflow.ui.PrimaryChip
import com.knoxx.dailyflow.ui.SecondaryChip
import com.knoxx.dailyflow.ui.SunkenPanel
import com.knoxx.dailyflow.ui.flowClickable
import com.knoxx.dailyflow.ui.theme.BorderStrong
import com.knoxx.dailyflow.ui.theme.CardWhite
import com.knoxx.dailyflow.ui.theme.Danger
import com.knoxx.dailyflow.ui.theme.Eyebrow
import com.knoxx.dailyflow.ui.theme.Ink
import com.knoxx.dailyflow.ui.theme.InkSecondary
import com.knoxx.dailyflow.ui.theme.MonoFigure
import com.knoxx.dailyflow.ui.theme.PaperSunken
import com.knoxx.dailyflow.ui.theme.Violet
import com.knoxx.dailyflow.ui.theme.VioletTint
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ModelSetupState(
    val installed: Boolean = false,
    val installedName: String? = null,
    val selected: ModelSpec = ModelCatalog.default,
    val resumableBytes: Long = 0,
    val downloading: ModelDownloader.Progress.Running? = null,
    val error: String? = null,
    val pushPath: String = ""
)

class ModelSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ModelSetupState())
    val state: StateFlow<ModelSetupState> = _state.asStateFlow()

    private var job: Job? = null

    init { refresh() }

    fun refresh() {
        val ctx = getApplication<Application>()
        val existing = ModelLocator.find(ctx)
        _state.value = _state.value.copy(
            installed = existing != null,
            installedName = existing?.name,
            pushPath = ModelLocator.pushPath(ctx),
            resumableBytes = ModelDownloader.partialBytes(ctx, _state.value.selected)
        )
    }

    fun select(spec: ModelSpec) {
        if (_state.value.downloading != null) return
        _state.value = _state.value.copy(
            selected = spec,
            error = null,
            resumableBytes = ModelDownloader.partialBytes(getApplication(), spec)
        )
    }

    fun download() {
        if (job?.isActive == true) return
        val ctx = getApplication<Application>()
        val spec = _state.value.selected
        _state.value = _state.value.copy(error = null)

        job = viewModelScope.launch {
            ModelDownloader.download(ctx, spec).collect { progress ->
                when (progress) {
                    is ModelDownloader.Progress.Running ->
                        _state.value = _state.value.copy(downloading = progress)

                    is ModelDownloader.Progress.Done -> {
                        _state.value = _state.value.copy(downloading = null, resumableBytes = 0)
                        refresh()
                    }

                    is ModelDownloader.Progress.Failed ->
                        _state.value = _state.value.copy(
                            downloading = null,
                            error = progress.reason,
                            resumableBytes = ModelDownloader.partialBytes(ctx, spec)
                        )
                }
            }
        }
    }

    /** Cancels but keeps the partial file, so the next attempt resumes. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = _state.value.copy(
            downloading = null,
            resumableBytes = ModelDownloader.partialBytes(getApplication(), _state.value.selected)
        )
    }

    fun discardPartial() {
        ModelDownloader.partFile(getApplication(), _state.value.selected).delete()
        _state.value = _state.value.copy(resumableBytes = 0, error = null)
    }
}

/**
 * How the model actually gets onto the phone.
 *
 * Without this the app is inert: capture still records screens, but nothing is
 * ever understood, and the only clue is an empty Today. So this is offered
 * unprompted the first time the app opens without a model, rather than hidden
 * in settings for the user to go looking for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSetupSheet(
    state: ModelSetupState,
    onSelect: (ModelSpec) -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDiscardPartial: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        // A download in flight must not be cancelled by a stray tap outside
        // the sheet; at ~3 GB that is an expensive mistake to make by accident.
        onDismissRequest = { if (state.downloading == null) onDismiss() },
        sheetState = sheetState,
        containerColor = CardWhite
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("ON-DEVICE MODEL", style = Eyebrow, color = InkSecondary)
            Text(
                if (state.installed) "Model installed" else "DailyFlow needs Gemma",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink
            )
            Text(
                if (state.installed) {
                    "${state.installedName} is ready. Everything is understood on this " +
                        "device from here on."
                } else {
                    "Downloaded once, then it works with no network at all. Nothing you " +
                        "capture is ever uploaded — this is what makes that true."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = InkSecondary
            )

            if (!state.installed) {
                ModelCatalog.all.forEach { spec ->
                    ModelOption(
                        spec = spec,
                        selected = spec.id == state.selected.id,
                        enabled = state.downloading == null,
                        onClick = { onSelect(spec) }
                    )
                }
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Danger)
            }

            val progress = state.downloading
            if (progress != null) {
                DownloadProgress(progress)
                SecondaryChip("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
                Text(
                    "Progress is kept — cancelling and retrying resumes where it stopped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary
                )
            } else if (!state.installed) {
                PrimaryChip(
                    label = if (state.resumableBytes > 0) {
                        "Resume — %.1f of %.1f GB done".format(
                            state.resumableBytes / 1_000_000_000.0,
                            state.selected.sizeGb
                        )
                    } else {
                        "Download %.1f GB".format(state.selected.sizeGb)
                    },
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth()
                )
                if (state.resumableBytes > 0) {
                    SecondaryChip(
                        "Discard partial download",
                        onClick = onDiscardPartial,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Kept as an escape hatch: at ~3 GB a bad network makes adb the
                // only reliable route, and it lands the file in the same place.
                SunkenPanel(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("OR SIDELOAD", style = Eyebrow, color = InkSecondary)
                        Text(
                            "adb push ${state.selected.fileName} ${state.pushPath}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = InkSecondary
                        )
                    }
                }
            } else {
                PrimaryChip("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ModelOption(
    spec: ModelSpec,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) VioletTint else PaperSunken)
            .border(1.dp, if (selected) Violet else BorderStrong, shape)
            .then(if (enabled) Modifier.flowClickable(onClick) else Modifier)
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                spec.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                modifier = Modifier.weight(1f)
            )
            Text("%.1f GB".format(spec.sizeGb), style = MonoFigure, color = InkSecondary)
        }
        Text(
            spec.note,
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun DownloadProgress(progress: ModelDownloader.Progress.Running) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(PaperSunken)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.fraction)
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Violet)
            )
        }
        Text(
            "%.2f / %.2f GB · %d%%".format(
                progress.bytesDownloaded / 1_000_000_000.0,
                progress.totalBytes / 1_000_000_000.0,
                (progress.fraction * 100).toInt()
            ),
            style = MonoFigure,
            color = Ink,
            fontWeight = FontWeight(700)
        )
        Text(
            buildString {
                append("%.1f MB/s".format(progress.bytesPerSecond / 1_000_000.0))
                if (progress.bytesPerSecond > 0) {
                    val secs = (progress.totalBytes - progress.bytesDownloaded) / progress.bytesPerSecond
                    append(" · ${secs / 60}m ${secs % 60}s left")
                }
            },
            style = MaterialTheme.typography.bodySmall,
            color = InkSecondary
        )
    }
}
