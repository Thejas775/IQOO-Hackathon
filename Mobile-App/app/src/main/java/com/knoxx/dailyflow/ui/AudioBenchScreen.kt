package com.knoxx.dailyflow.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.knoxx.dailyflow.llm.BackendChoice
import com.knoxx.dailyflow.llm.InferenceEngine
import com.knoxx.dailyflow.llm.ModelLocator
import com.knoxx.dailyflow.llm.WavTools
import com.knoxx.dailyflow.voice.VoiceRecorder
import java.io.File
import kotlinx.coroutines.launch

/**
 * Measures how long Gemma takes to ingest audio, as a function of clip length.
 *
 * The question this exists to answer: the companion device records a bounded take and
 * hands it to the phone, and the user stares at the screen until an answer appears. How
 * long is that wait at 60 s of audio? At 180 s? That number sets the recording cap on
 * the device, so it has to be measured before the device firmware commits to one.
 *
 * **Read TTFT, not the decode rate.** Time-to-first-token is prefill, and for an audio
 * turn prefill *is* the audio encode. The decode rate afterwards is the same text
 * generation the existing benchmark already characterises and is not what is in question
 * here. The headline derived figure is therefore **ms of wait per second of audio**,
 * which extrapolates to any cap.
 *
 * Worth knowing while reading results: `tf_lite_audio_encoder_hw` is ~837 MB and the
 * model's own metadata constrains it to CPU, so unlike the main model this cost may not
 * improve on the GPU backend. If GPU and CPU rows look identical, that is why.
 */
private const val PROMPT = "Transcribe this audio verbatim."

/** Clip lengths to offer, in seconds. 180 is the device's current recording cap. */
private val DURATIONS = listOf(10, 30, 60, 90, 120, 180)

private data class AudioRun(
    val seconds: Int,
    val ttftMs: Long,
    val decodeMs: Long,
    val totalMs: Long,
    val chars: Int,
    val backend: BackendChoice,
    val sourceWasSpeech: Boolean
) {
    /** The figure that extrapolates: wait per second of audio ingested. */
    val msPerAudioSecond: Double get() = if (seconds > 0) ttftMs.toDouble() / seconds else 0.0
}

@Composable
fun AudioBenchScreen(onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Own engine instance, as BenchScreen's view model does. Run this with the main app
    // UI swapped out and capture paused, so GemmaEngine is not also holding a copy —
    // two loaded E4Bs will not fit.
    val engine = remember { InferenceEngine(context) }
    val recorder = remember { VoiceRecorder(context) }

    var status by remember { mutableStateOf("No model loaded") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var sourcePcm by remember { mutableStateOf(ByteArray(0)) }
    var sourceIsSpeech by remember { mutableStateOf(false) }
    var runs by remember { mutableStateOf(listOf<AudioRun>()) }
    var lastReply by remember { mutableStateOf("") }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) status = "Microphone denied — use the tone source instead"
    }

    DisposableEffect(Unit) {
        onDispose { engine.close() }
    }

    fun load(backend: BackendChoice) {
        if (busy) return
        val model = ModelLocator.find(context)
        if (model == null) {
            status = "No .litertlm found. adb push one to ${ModelLocator.pushPath(context)}"
            return
        }
        busy = true
        loaded = false
        status = "Loading ${model.name} on $backend…"
        scope.launch {
            runCatching {
                engine.initialize(model.absolutePath, backend)
            }.onSuccess { active ->
                loaded = true
                busy = false
                status = "Loaded on $active" +
                    (if (active != backend) " (fell back from $backend)" else "") +
                    " · audio encoder on ${engine.activeVisionBackend}"
            }.onFailure { t ->
                busy = false
                status = "Load failed: ${t.message}"
            }
        }
    }

    fun run(seconds: Int) {
        if (busy || !loaded) return
        if (sourcePcm.isEmpty()) {
            status = "Pick a source first — tone or recording"
            return
        }
        busy = true
        status = "Building ${seconds}s clip…"
        scope.launch {
            runCatching {
                val clip = WavTools.makeClip(
                    sourcePcm, seconds, File(context.cacheDir, "bench-audio.wav")
                )
                status = "Encoding ${seconds}s of audio…"

                val started = System.currentTimeMillis()
                var firstAt = 0L
                var chars = 0
                val reply = StringBuilder()
                engine.chatStream(PROMPT, audioPath = clip.absolutePath).collect { delta ->
                    if (firstAt == 0L) firstAt = System.currentTimeMillis()
                    chars += delta.length
                    if (reply.length < 400) reply.append(delta)
                }
                val ended = System.currentTimeMillis()

                // Fresh conversation per run, matching the existing harness: it keeps
                // prefill from growing across runs, and it keeps every media turn the
                // first of its conversation, which is the documented condition for the
                // multimodal second-turn crash.
                engine.resetConversation()

                if (firstAt == 0L) error("Model produced no output")
                AudioRun(
                    seconds = seconds,
                    ttftMs = firstAt - started,
                    decodeMs = ended - firstAt,
                    totalMs = ended - started,
                    chars = chars,
                    backend = engine.activeBackend ?: BackendChoice.CPU,
                    sourceWasSpeech = sourceIsSpeech
                ) to reply.toString()
            }.onSuccess { (result, reply) ->
                runs = runs + result
                lastReply = reply
                busy = false
                status = "${seconds}s → waited ${fmt(result.ttftMs)} before the first word"
            }.onFailure { t ->
                busy = false
                status = "Run failed: ${t.message}"
            }
        }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()          // the app draws edge to edge; each screen insets itself
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onBack, enabled = !busy && !recording) { Text("Back") }
            }
            Text("Audio encode benchmark", style = MaterialTheme.typography.titleLarge)
            Text(
                "TTFT is the number that matters: for an audio turn, prefill is the " +
                    "audio encode. That is how long the device would sit on THINKING.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(4.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(4.dp))
            Text("1 · Load, choosing a backend", style = MaterialTheme.typography.titleSmall)
            Text(
                "The model is whatever ModelLocator finds on disk — the same file the app " +
                    "uses. These pick the backend only, and the ladder may fall back; the " +
                    "status line reports what actually took.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { load(BackendChoice.GPU) }, enabled = !busy) {
                    Text("GPU backend")
                }
                OutlinedButton(onClick = { load(BackendChoice.CPU) }, enabled = !busy) {
                    Text("CPU backend")
                }
            }

            Spacer(Modifier.height(4.dp))
            Text("2 · Choose a source", style = MaterialTheme.typography.titleSmall)
            Text(
                if (sourcePcm.isEmpty()) "No source yet"
                else "%.1fs of %s".format(
                    WavTools.seconds(sourcePcm), if (sourceIsSpeech) "speech" else "tone"
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        sourcePcm = WavTools.tone(10)
                        sourceIsSpeech = false
                        status = "Tone source ready — timing only, will not transcribe"
                    },
                    enabled = !busy && !recording
                ) { Text("Use tone") }

                Button(
                    onClick = {
                        if (recording) {
                            scope.launch {
                                val file = recorder.stop()
                                recording = false
                                if (file == null) {
                                    status = "Nothing recorded"
                                } else {
                                    sourcePcm = WavTools.pcmFrom(file)
                                    sourceIsSpeech = true
                                    status = "Recorded %.1fs of speech"
                                        .format(WavTools.seconds(sourcePcm))
                                }
                            }
                        } else {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            } else if (recorder.start()) {
                                recording = true
                                status = "Recording — speak, then press Stop"
                            } else {
                                status = "Could not open the microphone"
                            }
                        }
                    },
                    enabled = !busy
                ) { Text(if (recording) "Stop" else "Record") }
            }

            Spacer(Modifier.height(4.dp))
            Text("3 · Run a length", style = MaterialTheme.typography.titleSmall)
            // Two fixed rows rather than FlowRow, which is still behind
            // @ExperimentalLayoutApi in several Compose versions.
            DURATIONS.chunked(3).forEach { group ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { seconds ->
                        OutlinedButton(
                            onClick = { run(seconds) },
                            enabled = !busy && loaded && sourcePcm.isNotEmpty()
                        ) { Text("${seconds}s") }
                    }
                }
            }

            if (runs.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Results", style = MaterialTheme.typography.titleSmall)
                runs.forEach { r ->
                    Text(
                        "%3ds  wait %-7s  %.0f ms per audio-sec  decode %s  %d chars  %s%s"
                            .format(
                                r.seconds, fmt(r.ttftMs), r.msPerAudioSecond,
                                fmt(r.decodeMs), r.chars, r.backend,
                                if (r.sourceWasSpeech) "" else " (tone)"
                            ),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Projected from the longest run rather than the mean: encode cost is not
                // necessarily linear in duration, and if it is super-linear the short
                // clips would flatter the estimate — which is the direction that hurts.
                val longest = runs.maxByOrNull { it.seconds }
                if (longest != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "At the %ds rate, a 180s take would wait about %s."
                            .format(longest.seconds, fmt((longest.msPerAudioSecond * 180).toLong())),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (lastReply.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Last reply (truncated)", style = MaterialTheme.typography.titleSmall)
                Text(lastReply, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun fmt(ms: Long): String =
    if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
