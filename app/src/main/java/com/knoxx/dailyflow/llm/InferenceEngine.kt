package com.knoxx.dailyflow.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class BackendChoice { GPU, CPU }

/**
 * Thin wrapper over LiteRT-LM. One engine, one conversation at a time — the
 * runtime doesn't support multiple concurrent sessions per engine.
 *
 * Gemma 4 is natively multimodal; the vision encoder is loaded on demand, so
 * declaring [EngineConfig.visionBackend] costs nothing on text-only turns.
 */
class InferenceEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Which backend the *currently loaded* engine runs on. May differ from
     *  what was requested if GPU init failed and we fell back. */
    var activeBackend: BackendChoice? = null
        private set

    /** Which backend the vision encoder runs on. See the ladder in [initialize]
     *  — CPU here is a known-crashy last resort, and the UI warns about it. */
    var activeVisionBackend: BackendChoice? = null
        private set

    /** The KV-cache budget the loaded engine is actually running with. */
    var activeMaxNumTokens: Int? = null
        private set

    val isReady: Boolean get() = conversation != null

    /**
     * Loads [modelPath], degrading through a ladder of backend combinations.
     *
     * The ladder puts vision on the GPU even when the main backend has fallen
     * back to CPU, and that ordering is deliberate. With
     * `visionBackend = Backend.CPU()`, Gemma 4 E4B deterministically SIGSEGVs
     * in native `memmove` on the *second* image turn of a conversation —
     * reported on Snapdragon 8 Elite / Adreno, which is this project's target
     * class of device (google-ai-edge/litert-lm#2056). Moving only the vision
     * encoder to GPU eliminates it, independent of the main backend. So
     * CPU-main + GPU-vision is strictly better than falling back on both.
     *
     * Returns the backend the main model actually loaded on.
     */
    suspend fun initialize(
        modelPath: String,
        preferred: BackendChoice,
        maxNumTokens: Int = DEFAULT_MAX_NUM_TOKENS
    ): BackendChoice =
        withContext(Dispatchers.IO) {
            close()

            val ladder = buildList {
                if (preferred == BackendChoice.GPU) {
                    add(BackendChoice.GPU to BackendChoice.GPU)
                }
                add(BackendChoice.CPU to BackendChoice.GPU)
                add(BackendChoice.CPU to BackendChoice.CPU)
            }

            var lastFailure: Throwable? = null
            for ((main, vision) in ladder) {
                try {
                    load(modelPath, main, vision, maxNumTokens)
                    activeBackend = main
                    activeVisionBackend = vision
                    activeMaxNumTokens = maxNumTokens
                    if (vision == BackendChoice.CPU) {
                        Log.w(TAG, "Vision running on CPU — known unstable across image turns")
                    }
                    return@withContext main
                } catch (t: Throwable) {
                    Log.w(TAG, "Init failed for main=$main vision=$vision tokens=$maxNumTokens", t)
                    lastFailure = t
                    close()
                }
            }
            throw lastFailure ?: IllegalStateException("Engine failed to initialize")
        }

    private fun load(
        modelPath: String,
        main: BackendChoice,
        vision: BackendChoice,
        maxNumTokens: Int
    ) {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = main.toBackend(),
            visionBackend = vision.toBackend(),
            cacheDir = context.cacheDir.path,
            maxNumTokens = maxNumTokens
        )
        val e = Engine(config)
        e.initialize()
        engine = e
        conversation = e.createConversation()
    }

    private fun BackendChoice.toBackend(): Backend =
        if (this == BackendChoice.GPU) Backend.GPU() else Backend.CPU()

    /**
     * Streams the reply as incremental text deltas. Pass [imagePath] to send a
     * picture alongside the prompt.
     *
     * The image goes first in [Contents.of]: Gemma's multimodal template
     * expects the image to precede the text that refers to it, and reversing
     * them produces answers that ignore the picture.
     *
     * Not marked suspend — the caller collects this and timestamps each
     * emission itself, which is how TTFT and decode rate get measured. Timing
     * in here would fold collector-side delay into the numbers.
     */
    fun chatStream(userMessage: String, imagePath: String? = null): Flow<String> {
        val conv = conversation ?: error("Engine not initialized")
        val contents = if (imagePath != null) {
            Contents.of(Content.ImageFile(imagePath), Content.Text(userMessage))
        } else {
            Contents.of(Content.Text(userMessage))
        }
        return conv.sendMessageAsync(contents)
            .map { it.toString() }
            .flowOn(Dispatchers.IO)
    }

    /**
     * Fresh conversation, same loaded engine.
     *
     * Doubles as a safety net for #2056: that crash needs a *second* image turn
     * within one conversation, so resetting after every run means every image
     * turn is the first of its conversation. Belt and braces alongside the
     * GPU vision backend.
     */
    fun resetConversation() {
        val e = engine ?: return
        closeConversation()
        conversation = e.createConversation()
    }

    private fun closeConversation() {
        try {
            (conversation as? AutoCloseable)?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close conversation", t)
        }
        conversation = null
    }

    fun close() {
        closeConversation()
        try {
            (engine as? AutoCloseable)?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close engine", t)
        }
        engine = null
        activeBackend = null
        activeVisionBackend = null
    }

    companion object {
        private const val TAG = "InferenceEngine"

        /**
         * LiteRT-LM's own default if `maxNumTokens` is omitted — confirmed by
         * reading the engine's validated settings on device, which printed
         * `max_tokens: 4096`. Gemma 4 E4B itself supports up to 32k, so leaving
         * this unset silently discards seven eighths of the usable window.
         *
         * It is not free to raise: this is the **KV-cache budget**, and memory
         * scales with it. There are also field reports of instability above
         * 4096 — `litert#6765` (SIGSEGV above 4096 on iOS arm64) and
         * `litert-lm#1681`, where a team settled on 3072 as their production
         * value to stay "safely below the crash threshold" on Tensor G5. Both
         * are other devices; treat larger values as unverified until measured
         * on the actual target, which is what the in-app selector is for.
         */
        const val DEFAULT_MAX_NUM_TOKENS = 4096

        /** Offered in the UI so the memory and stability cost of a bigger
         *  window can be measured rather than assumed. */
        val CONTEXT_OPTIONS = listOf(4096, 8192, 16384, 32768)
    }
}
