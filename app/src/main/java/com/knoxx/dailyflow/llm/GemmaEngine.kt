package com.knoxx.dailyflow.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide holder for the one loaded Gemma engine.
 *
 * There is exactly one, and access is serialised. LiteRT-LM supports a single
 * conversation per engine, and three separate features want the model —
 * extraction in a worker, Ask on the UI thread, and the morning brief — so
 * without a shared lock they would interleave into one conversation and
 * corrupt each other's output. A second engine is not an option either: the
 * model is ~3.5 GB and loading it twice would not fit.
 *
 * Loading is lazy and idempotent. Nothing loads the model on app start,
 * because a user who never triggers inference should not pay ~10 seconds of
 * startup and a gigabyte of memory for it.
 */
object GemmaEngine {

    private const val TAG = "GemmaEngine"

    /**
     * Context length, and LiteRT-LM's own default.
     *
     * 32768 was measured loading fine on this device and was used for a while
     * on that basis, but the measurement was misleading in two ways. It was
     * taken against the GPU build, which keeps weights in GPU memory, and the
     * KV cache allocates lazily — so load looked cheap and the cost only
     * arrived during generation. Against the universal build, whose weights
     * sit in system RAM, a 32K cache on top was enough to trigger a
     * system-wide low-memory kill: the phone froze whenever a question was
     * asked or an extraction pass ran.
     *
     * It went to 8192 for batched extraction and came back when batching was
     * removed. One screen is roughly 750 tokens of input and a ~120 token
     * reply, so 4096 is ample and the unused KV cache is not worth paying for.
     *
     * That freeze, for the record, was never traced to memory at all: the logs
     * showed no OOM, no kill, and a crash loop belonging to a vivo system app.
     * So this constant is sized for the work, not defensively.
     */
    private const val MAX_TOKENS = 4096

    enum class State { Absent, Loading, Ready, Failed }

    private val _state = MutableStateFlow(State.Absent)
    val state: StateFlow<State> = _state.asStateFlow()

    private var errorMessage: String? = null
    val lastError: String? get() = errorMessage

    private var engine: InferenceEngine? = null

    /** Guards the engine itself. Every caller runs its whole exchange —
     *  prompt in, full response out — inside this lock. */
    private val mutex = Mutex()

    /** True when a model file is present, whether or not it is loaded yet. */
    fun isModelAvailable(context: Context): Boolean = ModelLocator.find(context) != null

    /**
     * Loads on first use. Callers that merely want to know whether inference
     * is possible should use [isModelAvailable] instead — this blocks for as
     * long as the model takes to load.
     */
    private suspend fun ensureLoaded(context: Context): InferenceEngine? {
        engine?.let { if (it.isReady) return it }

        val model = ModelLocator.find(context)
        if (model == null) {
            _state.value = State.Absent
            return null
        }

        _state.value = State.Loading
        return try {
            val fresh = InferenceEngine(context.applicationContext)
            fresh.initialize(model.absolutePath, BackendChoice.GPU, MAX_TOKENS)
            engine = fresh
            errorMessage = null
            _state.value = State.Ready
            fresh
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load model", t)
            errorMessage = t.message
            _state.value = State.Failed
            null
        }
    }

    /**
     * Runs one prompt to completion and returns the whole reply.
     *
     * Every exchange starts from a fresh conversation. That is not just
     * hygiene: leaving history in place means each turn's prefill grows with
     * everything before it, so extraction would get steadily slower through
     * the day, and one screen's content could leak into the summary of the
     * next. Each capture is judged on its own.
     */
    suspend fun complete(context: Context, prompt: String): String? = mutex.withLock {
        val e = ensureLoaded(context) ?: return@withLock null
        try {
            e.resetConversation()
            val builder = StringBuilder()
            e.chatStream(prompt).collect { builder.append(it) }
            builder.toString().trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Inference failed", t)
            null
        }
    }

    /** Streaming variant for Ask, where tokens should appear as they arrive.
     *  Holds the lock for the whole stream, so extraction waits its turn. */
    suspend fun streamInto(
        context: Context,
        prompt: String,
        onDelta: suspend (String) -> Unit
    ): Boolean = mutex.withLock {
        val e = ensureLoaded(context) ?: return@withLock false
        try {
            e.resetConversation()
            e.chatStream(prompt).collect { onDelta(it) }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Streaming inference failed", t)
            false
        }
    }

    /** Frees the model. Reloads on next use, at the cost of the load time. */
    suspend fun unload() = mutex.withLock {
        engine?.close()
        engine = null
        _state.value = State.Absent
    }

    /**
     * Held while a background pass owns the engine.
     *
     * The activity and the extraction worker share this one engine, so
     * backgrounding the app during a pass would otherwise unload the model out
     * from under it — the unload waits on the mutex, frees the engine, and the
     * worker's next inference reloads it, thrashing several gigabytes in a
     * loop. A pinned engine ignores lifecycle unload requests; the pass
     * releases it itself when it finishes.
     */
    private var pinned = false

    fun pin() {
        pinned = true
    }

    fun unpin() {
        pinned = false
    }

    /**
     * Fire-and-forget unload for lifecycle callbacks.
     *
     * Deliberately not tied to the activity's own scope: that scope is
     * cancelled when the activity is destroyed, which is exactly the moment
     * this needs to run, so the unload would be cancelled before it took the
     * mutex and several gigabytes would stay resident in a process about to be
     * backgrounded.
     */
    fun requestUnload() {
        if (pinned) return
        ownScope.launch { unload() }
    }

    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Suspends until the model is loaded or known unavailable, without
     *  holding the lock — for a UI that wants to show a loading state. */
    suspend fun awaitReady(context: Context): Boolean {
        if (_state.value == State.Ready) return true
        mutex.withLock { ensureLoaded(context) }
        return state.first { it != State.Loading } == State.Ready
    }
}
