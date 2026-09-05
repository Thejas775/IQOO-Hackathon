package com.knoxx.dailyflow.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.knoxx.dailyflow.BenchUiState
import com.knoxx.dailyflow.ChatMessage
import com.knoxx.dailyflow.ModelStatus
import com.knoxx.dailyflow.llm.BackendChoice
import com.knoxx.dailyflow.llm.GenerationStats
import com.knoxx.dailyflow.llm.InferenceEngine
import com.knoxx.dailyflow.llm.ModelCatalog
import com.knoxx.dailyflow.llm.ModelSpec
import com.knoxx.dailyflow.vision.CameraCapture

@Composable
fun BenchScreen(
    state: BenchUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onSetBackend: (BackendChoice) -> Unit,
    onRetryModel: () -> Unit,
    onClearRuns: () -> Unit,
    onSetMaxNumTokens: (Int) -> Unit,
    onSelectSpec: (ModelSpec) -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDiscardPartial: () -> Unit,
    onAttachImage: (Uri) -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        HeaderBar(state, onSetBackend, onClearRuns, onSetMaxNumTokens)

        when (state.modelStatus) {
            ModelStatus.Missing ->
                ModelSetupPanel(
                    state = state,
                    onSelectSpec = onSelectSpec,
                    onStartDownload = onStartDownload,
                    onDiscardPartial = onDiscardPartial,
                    onRetryModel = onRetryModel,
                    modifier = Modifier.weight(1f)
                )

            ModelStatus.Downloading ->
                DownloadingPanel(state, onCancelDownload, Modifier.weight(1f))

            ModelStatus.Error ->
                CenteredMessage(
                    title = "Model failed to load",
                    body = state.errorMessage,
                    actionLabel = "Retry",
                    onAction = onRetryModel,
                    modifier = Modifier.weight(1f)
                )

            ModelStatus.Checking, ModelStatus.Loading ->
                CenteredProgress(
                    label = if (state.modelStatus == ModelStatus.Loading)
                        "Loading ${state.modelName}…" else "Looking for a model…",
                    modifier = Modifier.weight(1f)
                )

            ModelStatus.Ready -> {
                TranscriptList(state.messages, Modifier.weight(1f))
                if (state.errorMessage.isNotBlank()) {
                    Text(
                        state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
                Composer(state, onSend, onStop, onAttachImage, onClearImage)
            }
        }
    }
}

@Composable
private fun HeaderBar(
    state: BenchUiState,
    onSetBackend: (BackendChoice) -> Unit,
    onClearRuns: () -> Unit,
    onSetMaxNumTokens: (Int) -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Token throughput", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.modelName.isNotBlank())
                            "${state.modelName} · ${state.modelSizeMb} MB"
                        else "no model loaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (state.runs.isNotEmpty()) {
                    TextButton(onClick = onClearRuns) { Text("Reset") }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackendChoice.entries.forEach { choice ->
                    FilterChip(
                        selected = state.requestedBackend == choice,
                        onClick = { onSetBackend(choice) },
                        enabled = !state.isGenerating,
                        label = { Text(choice.name) }
                    )
                }
                // Surfaced explicitly: a silent GPU→CPU fallback would otherwise
                // look like "the GPU is just slow" in the numbers.
                if (state.activeBackend != null && state.activeBackend != state.requestedBackend) {
                    Text(
                        "running on ${state.activeBackend.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Surfaced as a control, not a constant: this is the KV-cache
            // budget, so it is the main lever on both memory and how much of a
            // captured screen can be fed in at once. Omitting it silently
            // caps the window at 4096 even though E4B supports 32k.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "CTX",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                InferenceEngine.CONTEXT_OPTIONS.forEach { tokens ->
                    FilterChip(
                        selected = state.requestedMaxNumTokens == tokens,
                        onClick = { onSetMaxNumTokens(tokens) },
                        enabled = !state.isGenerating,
                        label = { Text("${tokens / 1024}K") }
                    )
                }
            }

            // CPU vision is the last rung of the init ladder and is known to
            // SIGSEGV across image turns (litert-lm#2056). Say so rather than
            // letting it look like a random native crash later.
            if (state.activeVisionBackend == BackendChoice.CPU) {
                Text(
                    "Vision is on CPU — image turns may be unstable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            if (state.runs.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                SessionSummary(state)
            }
        }
    }
}

@Composable
private fun SessionSummary(state: BenchUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Metric("best", "%.1f".format(state.bestDecodeRate), "tok/s")
        Metric(
            if (state.warmRunCount > 0) "mean (warm)" else "mean",
            if (state.warmRunCount > 0) "%.1f".format(state.meanDecodeRate) else "—",
            "tok/s"
        )
        Metric("runs", "${state.runs.size}", "")
    }
    if (state.imageRuns.isNotEmpty()) {
        Text(
            "vision prefill %d ms · text prefill %d ms"
                .format(state.meanImageTtftMs, state.meanTextTtftMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    if (state.runs.size == 1) {
        Text(
            "First run includes one-time warm-up — send another for a representative number.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String) {
    Column {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace)
            if (unit.isNotEmpty()) {
                Text(" $unit", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TranscriptList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Send a prompt to measure decode speed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages, key = { it.id }) { MessageRow(it) }
    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    Column(Modifier.fillMaxWidth()) {
        message.imagePath?.let {
            ThumbnailImage(it, size = 140.dp)
            Spacer(Modifier.height(6.dp))
        }
        Surface(
            color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = message.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp)
            )
        }
        message.stats?.let { StatsRow(it) }
    }
}

@Composable
private fun StatsRow(s: GenerationStats) {
    Text(
        text = "%.1f tok/s decode · %d ms to first token%s · %d tok · %.0f char/s · %s"
            .format(
                s.decodeTokensPerSec,
                s.ttftMs,
                if (s.hadImage) " (vision)" else "",
                s.tokens,
                s.charsPerSec,
                s.backend.name
            ),
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    )
}

@Composable
private fun Composer(
    state: BenchUiState,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onAttachImage: (Uri) -> Unit,
    onClearImage: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val context = LocalContext.current
    val isGenerating = state.isGenerating

    // Held across the launcher round trip: TakePicture only reports success,
    // it doesn't hand back the URI it wrote to.
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        pendingCaptureUri?.let { if (success) onAttachImage(it) }
        pendingCaptureUri = null
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onAttachImage) }

    fun submit() {
        if (text.isNotBlank() || state.attachedImagePath != null) {
            onSend(text)
            text = ""
        }
    }

    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {

            state.attachedImagePath?.let { path ->
                AttachedImagePreview(path, onClearImage)
                Spacer(Modifier.height(8.dp))
                // One-tap prompts for the two things this is actually for.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickPrompt("Describe") { onSend("Describe this image in detail."); text = "" }
                    QuickPrompt("Read the text") {
                        onSend(
                            "Transcribe all text visible in this image, exactly as written, " +
                                "preserving line breaks. Output only the text."
                        )
                        text = ""
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            if (state.isPreparingImage) {
                Text(
                    "Preparing image…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(
                    enabled = !isGenerating,
                    onClick = {
                        val uri = CameraCapture.newCaptureUri(context)
                        pendingCaptureUri = uri
                        takePicture.launch(uri)
                    }
                ) { Icon(Icons.Default.PhotoCamera, contentDescription = "Take a photo") }

                IconButton(
                    enabled = !isGenerating,
                    onClick = {
                        pickImage.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                ) { Icon(Icons.Default.Image, contentDescription = "Pick an image") }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (state.attachedImagePath != null) "Ask about the image" else "Prompt")
                    },
                    enabled = !isGenerating,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )

                if (isGenerating) {
                    Button(onClick = onStop) { Text("Stop") }
                } else {
                    Button(
                        onClick = { submit() },
                        enabled = text.isNotBlank() || state.attachedImagePath != null
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun QuickPrompt(label: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(20.dp),
        onClick = onClick
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun AttachedImagePreview(path: String, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ThumbnailImage(path, size = 64.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Image attached",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) { Text("Remove") }
    }
}

/**
 * Decodes straight off disk rather than pulling in an image-loading library.
 * Safe here because [ImagePrep] has already capped the file at 1024px on its
 * longest edge, so this is a small, fast decode — and it's cached by [remember]
 * against the path, so it happens once per image rather than per recomposition.
 */
@Composable
private fun ThumbnailImage(path: String, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun ModelSetupPanel(
    state: BenchUiState,
    onSelectSpec: (ModelSpec) -> Unit,
    onStartDownload: () -> Unit,
    onDiscardPartial: () -> Unit,
    onRetryModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text("Get a model", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Gemma 4 E4B runs entirely on this device. Downloaded once, then it works offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        ModelCatalog.all.forEach { spec ->
            ModelOptionRow(
                spec = spec,
                selected = spec.id == state.selectedSpec.id,
                onClick = { onSelectSpec(spec) }
            )
            Spacer(Modifier.height(8.dp))
        }

        if (state.errorMessage.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                state.errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))

        val resumable = state.resumableBytes > 0
        Button(onClick = onStartDownload, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (resumable)
                    "Resume download (%.1f of %.1f GB done)".format(
                        state.resumableBytes / 1_000_000_000.0, state.selectedSpec.sizeGb
                    )
                else "Download %.1f GB".format(state.selectedSpec.sizeGb)
            )
        }

        if (resumable) {
            TextButton(onClick = onDiscardPartial, modifier = Modifier.fillMaxWidth()) {
                Text("Discard partial download and start over")
            }
        }

        Spacer(Modifier.height(28.dp))

        // Kept as an escape hatch: at ~3 GB, a bad venue network makes adb the
        // only reliable route, and it lands the file in the same place.
        Text("OR SIDELOAD", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "adb push ${state.selectedSpec.fileName} \\\n  ${state.pushPath}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(12.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetryModel) { Text("I've pushed it — check again") }
    }
}

@Composable
private fun ModelOptionRow(spec: ModelSpec, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "%.1f GB".format(spec.sizeGb),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                spec.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadingPanel(
    state: BenchUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = state.download

    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(state.selectedSpec.displayName, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))

        if (progress == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("Connecting…", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "%.2f / %.2f GB · %d%%".format(
                    progress.bytesDownloaded / 1_000_000_000.0,
                    progress.totalBytes / 1_000_000_000.0,
                    (progress.fraction * 100).toInt()
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("%.1f MB/s".format(progress.bytesPerSecond / 1_000_000.0))
                    val remaining = progress.totalBytes - progress.bytesDownloaded
                    if (progress.bytesPerSecond > 0) {
                        val secs = remaining / progress.bytesPerSecond
                        append(" · ${secs / 60}m ${secs % 60}s left")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
        Text(
            "Progress is kept — cancelling and retrying resumes where it stopped.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CenteredProgress(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CenteredMessage(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAction) { Text(actionLabel) }
    }
}
