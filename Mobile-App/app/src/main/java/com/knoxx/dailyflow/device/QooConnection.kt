package com.knoxx.dailyflow.device

import android.content.Context
import android.util.Log
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.AskPrompt
import com.knoxx.dailyflow.llm.ExtractionParser
import com.knoxx.dailyflow.llm.GemmaEngine
import com.knoxx.dailyflow.llm.MemorySearch
import com.knoxx.dailyflow.llm.VoicePrompt
import com.knoxx.dailyflow.llm.WavTools
import com.knoxx.dailyflow.ui.QOO_PACKAGE
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-wide holder for the watch connection.
 *
 * A singleton rather than screen state, and that is the whole point: the pairing used to
 * live in the device screen's composition, so walking back to Today disposed the socket
 * and silently unpaired the watch. A companion device that only stays connected while
 * you are looking at its settings page is not connected in any useful sense. Once paired
 * it stays paired until [disconnect] or the link itself drops.
 *
 * Holds the application context only. An Activity reference here would outlive the
 * Activity, which is the ordinary way a singleton like this leaks a whole screen.
 *
 * Same shape as [GemmaEngine] for the same reason: one of these exists, several screens
 * want to see it, and its lifetime is the process rather than any one composition.
 */
object QooConnection {

    private const val TAG = "QooConnection"

    enum class Status { Idle, Searching, Pairing, Paired, Failed }

    data class State(
        val status: Status = Status.Idle,
        val message: String = "Enter the code shown on the watch",
        val log: List<String> = emptyList(),
        val code: String = ""
    ) {
        val isPaired: Boolean get() = status == Status.Paired
        val isBusy: Boolean get() = status == Status.Searching || status == Status.Pairing
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var link: QooLink? = null
    private var receiveJob: Job? = null

    private fun say(message: String, status: Status? = null) {
        val current = _state.value
        _state.value = current.copy(
            status = status ?: current.status,
            message = message,
            log = (listOf(message) + current.log).take(14)
        )
        DebugLog.log("qoo", message)
    }

    /** Finds the watch advertising [code], pairs with it, and starts listening. */
    fun connect(context: Context, code: String) {
        if (_state.value.isBusy || _state.value.isPaired) return
        val app = context.applicationContext

        _state.value = _state.value.copy(status = Status.Searching, code = code)
        say("Looking for the watch...")

        scope.launch {
            val fresh = QooLink()
            val ip = fresh.discover(code)
            if (ip == null) {
                _state.value = _state.value.copy(status = Status.Failed)
                say("No watch answering with that code. Same network, and code still on screen?")
                return@launch
            }
            _state.value = _state.value.copy(status = Status.Pairing)
            say("Found it at $ip - pairing...")

            if (!fresh.connect(ip, code)) {
                _state.value = _state.value.copy(status = Status.Failed)
                say("The watch refused that code")
                return@launch
            }
            link = fresh
            _state.value = _state.value.copy(status = Status.Paired)
            say("Paired. Press the watch whenever you like.")
            receiveJob = scope.launch { receiveLoop(app, fresh) }
        }
    }

    /** Drops the link deliberately. The watch returns to showing its pairing code. */
    fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null
        link?.close()
        link = null
        _state.value = State(message = "Disconnected")
        DebugLog.log("qoo", "disconnected by the user")
    }

    /**
     * Handles everything the watch sends, for as long as the link lasts.
     *
     * One message at a time, to completion. That is also the backpressure: the watch
     * cannot start another take until someone presses it again, and two inferences
     * running at once would contend for the one engine anyway.
     */
    private suspend fun receiveLoop(context: Context, link: QooLink) {
        while (true) {
            val message = link.awaitMessage() ?: break
            val pcm = message.pcm
            val seconds = pcm.size.toDouble() / WavTools.BYTES_PER_SECOND

            // Wrapped here rather than on the watch: a WAV header needs a length only
            // known once recording stops, and this is byte for byte the file
            // VoiceRecorder would have written.
            val wav = WavTools.writeWav(File(context.cacheDir, "qoo-note.wav"), pcm)

            if (message.kind == QooLink.Kind.QUESTION) {
                answer(context, link, wav, seconds)
            } else {
                remember(context, wav, seconds)
            }
        }
        _state.value = _state.value.copy(status = Status.Idle)
        say("The watch disconnected")
    }

    /** A spoken question: transcribe, search, answer, speak it back. */
    private suspend fun answer(context: Context, link: QooLink, wav: File, seconds: Double) {
        say("Heard a question (%.0fs) - thinking...".format(seconds))

        // Two inferences, not one. Answering needs memories retrieved for this question,
        // and retrieval needs the words, which only exist once the audio is transcribed.
        // No ordering collapses them into one call.
        val question = GemmaEngine.completeWithAudio(
            context, wav.absolutePath,
            "Write out exactly what was said in this audio. Reply with only those words."
        )?.trim()
        if (question.isNullOrBlank()) {
            say("Could not make out the question")
            return
        }
        say("Asked: $question")

        val hits = MemorySearch.search(context, question)
        val reply = StringBuilder()
        val ok = GemmaEngine.streamInto(context, AskPrompt.build(question, hits)) { delta ->
            reply.append(delta)
        }
        val spoken = reply.toString().trim()
        if (!ok || spoken.isBlank()) {
            say("No answer came back")
            return
        }
        say("Answer: ${spoken.take(90)}")

        // Words first, and before synthesis rather than after: the watch can show the
        // answer while speech is still being generated, and if synthesis fails or the
        // speaker is dead the answer still arrives. Sending it only alongside the audio
        // would make reading the answer depend on hearing it.
        link.sendText(spoken)

        val voice = SpokenAnswer.render(context, spoken)
        if (voice == null) {
            say("Answer shown on the watch, but it could not be spoken")
            return
        }
        if (link.sendReply(voice)) {
            say("Answered out loud")
        } else {
            say("The answer could not be sent to the watch")
        }
    }

    /** A spoken note: understand it and save it, badged QOO. */
    private suspend fun remember(context: Context, wav: File, seconds: Double) {
        say("Heard %.0fs - reading it...".format(seconds))

        val reply = GemmaEngine.completeWithAudio(context, wav.absolutePath, VoicePrompt.build())
        if (reply == null) {
            say("The model is not loaded, so that cannot be read yet")
            return
        }

        val items = ExtractionParser.parseAll(reply, "").filter { it.isMemory }
        items.forEach { item ->
            MemoryRepository.addManual(
                title = item.summary.orEmpty(),
                dueAt = item.dueAt,
                category = null,
                personName = item.person,
                dueAtExplicit = item.dueAtExplicit,
                packageName = QOO_PACKAGE,
                eventType = "QOO",
                // Carried through rather than defaulted: the open-commitments query
                // selects on SELF_PROMISE / THEY_ASKED, so dropping this put the
                // watch's memories in the timeline and nowhere else.
                direction = item.direction
            )
        }
        say(if (items.isEmpty()) "Nothing to act on in that one" else "Saved ${items.size} to your day")
        Log.i(TAG, "saved ${items.size} memories from the watch")
    }
}
