package com.knoxx.dailyflow.device

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns an answer into 16 kHz mono PCM the watch can play.
 *
 * Android has no PCM-returning synthesis API, so this goes through
 * `synthesizeToFile`, then decodes and resamples whatever the engine produced. Engines
 * emit 22.05 or 24 kHz routinely, and the watch is fixed at 16 kHz to match its
 * microphone, so resampling here keeps one audio format across the whole device.
 *
 * **An offline voice is preferred where one exists.** Google's engine will happily use a
 * network voice, and DailyFlow's whole claim is that nothing leaves the phone — sending
 * the text of an answer about someone's private commitments to a speech server would
 * break that quietly, in the one place nobody would look. Where no offline voice is
 * installed this still speaks rather than failing, but it says so in the log.
 */
object SpokenAnswer {

    private const val TAG = "SpokenAnswer"

    /** The rate the watch plays at. Matches its microphone, so one format serves both. */
    const val TARGET_RATE = 16_000

    /**
     * Synthesises [text] and returns it as 16 kHz mono 16-bit PCM, or null on failure.
     */
    suspend fun render(context: Context, text: String): ByteArray? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext null
        val engine = awaitEngine(context) ?: return@withContext null
        try {
            preferOfflineVoice(engine)
            val file = File(context.cacheDir, "qoo-answer.wav")
            if (!synthesise(engine, text, file)) return@withContext null
            decodeToTargetRate(file)
        } finally {
            runCatching { engine.shutdown() }
        }
    }

    /** TextToSpeech signals readiness through a callback, so the constructor is wrapped. */
    private suspend fun awaitEngine(context: Context): TextToSpeech? =
        withTimeoutOrNull(8_000) {
            suspendCancellableCoroutine { cont ->
                var tts: TextToSpeech? = null
                tts = TextToSpeech(context) { status ->
                    if (cont.isActive) {
                        if (status == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            cont.resume(tts)
                        } else {
                            Log.e(TAG, "TTS init failed with status $status")
                            cont.resume(null)
                        }
                    }
                }
            }
        }

    /** Picks a voice that does not need the network, when the engine offers one. */
    private fun preferOfflineVoice(engine: TextToSpeech) {
        val offline = runCatching {
            engine.voices?.firstOrNull { voice ->
                !voice.isNetworkConnectionRequired &&
                    voice.locale.language == Locale.getDefault().language &&
                    Voice.QUALITY_NORMAL <= voice.quality
            }
        }.getOrNull()

        if (offline != null) {
            engine.voice = offline
            Log.i(TAG, "using offline voice ${offline.name}")
        } else {
            // Worth saying out loud: this is the one path where answer text could leave
            // the phone, and it is invisible from the UI.
            Log.w(TAG, "no offline voice installed - the engine may synthesise over the network")
        }
    }

    /** synthesizeToFile is asynchronous; this waits for the matching utterance callback. */
    private suspend fun synthesise(engine: TextToSpeech, text: String, file: File): Boolean =
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine { cont ->
                val id = "qoo-answer"
                engine.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        @Deprecated("Required by the abstract class")
                        override fun onError(utteranceId: String?) {
                            if (cont.isActive) cont.resume(false)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            Log.e(TAG, "synthesis failed, code $errorCode")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                )
                val queued = engine.synthesizeToFile(text, null, file, id)
                if (queued != TextToSpeech.SUCCESS && cont.isActive) {
                    cont.resume(false)
                }
            }
        } ?: false

    /**
     * Reads a WAV and returns 16 kHz mono 16-bit PCM.
     *
     * Chunks are walked rather than assuming a 44-byte header: TTS engines insert LIST
     * and fact chunks, and a fixed offset would slice audio off the front or feed
     * metadata to the speaker as if it were sound.
     */
    private fun decodeToTargetRate(file: File): ByteArray? {
        val bytes = file.readBytes()
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF") {
            Log.e(TAG, "not a RIFF file")
            return null
        }

        var channels = 1
        var rate = TARGET_RATE
        var bits = 16
        var dataAt = -1
        var dataLength = 0

        var pos = 12
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val size = intLe(bytes, pos + 4)
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    channels = shortLe(bytes, pos + 10)
                    rate = intLe(bytes, pos + 12)
                    bits = shortLe(bytes, pos + 22)
                }
                "data" -> {
                    dataAt = pos + 8
                    dataLength = minOf(size, bytes.size - dataAt)
                }
            }
            if (dataAt >= 0) break
            pos += 8 + size + (size and 1)        // chunks are word-aligned
        }

        if (dataAt < 0 || dataLength <= 0) {
            Log.e(TAG, "no data chunk")
            return null
        }
        if (bits != 16) {
            Log.e(TAG, "unsupported bit depth $bits")
            return null
        }

        // To mono first, then resample: averaging after resampling would interpolate
        // between two different channels' samples.
        val samples = ShortArray(dataLength / 2 / channels)
        for (i in samples.indices) {
            var sum = 0
            for (c in 0 until channels) {
                val at = dataAt + (i * channels + c) * 2
                sum += (bytes[at].toInt() and 0xff or (bytes[at + 1].toInt() shl 8)).toShort()
            }
            samples[i] = (sum / channels).toShort()
        }

        val resampled = if (rate == TARGET_RATE) samples else resample(samples, rate, TARGET_RATE)
        val out = ByteArray(resampled.size * 2)
        for (i in resampled.indices) {
            out[i * 2] = (resampled[i].toInt() and 0xff).toByte()
            out[i * 2 + 1] = ((resampled[i].toInt() shr 8) and 0xff).toByte()
        }
        Log.i(TAG, "answer: ${rate}Hz x$channels -> ${TARGET_RATE}Hz mono, ${out.size} bytes")
        return out
    }

    /** Linear interpolation. Ample for speech at these rates, and dependency-free. */
    private fun resample(input: ShortArray, from: Int, to: Int): ShortArray {
        if (input.isEmpty()) return input
        val outLength = (input.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = ShortArray(outLength)
        val step = input.size.toDouble() / outLength
        for (i in 0 until outLength) {
            val at = i * step
            val low = at.toInt().coerceAtMost(input.size - 1)
            val high = (low + 1).coerceAtMost(input.size - 1)
            val frac = at - low
            out[i] = (input[low] * (1 - frac) + input[high] * frac).toInt().toShort()
        }
        return out
    }

    private fun intLe(b: ByteArray, at: Int): Int =
        if (at + 4 > b.size) -1
        else (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or
            ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    private fun shortLe(b: ByteArray, at: Int): Int =
        if (at + 2 > b.size) 0
        else (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
}
