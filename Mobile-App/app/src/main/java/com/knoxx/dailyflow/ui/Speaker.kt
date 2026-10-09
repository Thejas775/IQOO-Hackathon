package com.knoxx.dailyflow.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Text-to-speech for "Play brief".
 *
 * Held as a remembered object with an explicit disposal, because
 * TextToSpeech holds a binding to a system service: leaking one keeps that
 * connection alive after the screen is gone, and Android logs it loudly.
 */
class Speaker(context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
                // Engine init is asynchronous and typically slower than the
                // user's tap, so a request made before it finished is held and
                // spoken here rather than silently dropped.
                pending?.let { speak(it) }
                pending = null
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        val engine = tts
        if (engine == null || !ready) {
            pending = text
            return
        }
        // A short silence before the utterance: some engines clip the first
        // syllable when speaking immediately after being handed focus.
        engine.playSilentUtterance(150, TextToSpeech.QUEUE_FLUSH, null)
        engine.speak(text, TextToSpeech.QUEUE_ADD, null, "brief")
    }

    fun stop() {
        tts?.stop()
        pending = null
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** Ties a [Speaker] to the composition, shutting it down on the way out. */
@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) {
        onDispose { speaker.shutdown() }
    }
    return speaker
}
