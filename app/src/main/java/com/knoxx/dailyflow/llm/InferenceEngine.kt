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
 */
class InferenceEngine(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Which backend the *currently loaded* engine is actually running on.
     *  May differ from what was requested if GPU init failed and we fell back. */
    var activeBackend: BackendChoice? = null
        private set

    val isReady: Boolean get() = conversation != null

    /**
     * Loads [modelPath] on [preferred], falling back to CPU if GPU init fails.
     *
     * Note the fallback wraps `Engine(...)` and `initialize()`, not just the
     * config construction. EngineConfig is an inert value object — building one
     * with an unsupported backend never throws. The failure only surfaces when
     * the native engine actually initializes, so a try/catch around config
     * construction alone is dead code that never fires, and the app reports a
     * hard load failure on exactly the devices the fallback existed to rescue.
     *
     * Returns the backend that actually loaded.
     */
    suspend fun initialize(modelPath: String, preferred: BackendChoice): BackendChoice =
        withContext(Dispatchers.IO) {
            close()

            if (preferred == BackendChoice.GPU) {
                try {
                    load(modelPath, Backend.GPU())
                    activeBackend = BackendChoice.GPU
                    return@withContext BackendChoice.GPU
                } catch (t: Throwable) {
                    Log.w(TAG, "GPU backend failed to initialize, falling back to CPU", t)
                    close()
                }
            }

            load(modelPath, Backend.CPU())
            activeBackend = BackendChoice.CPU
            BackendChoice.CPU
        }

    private fun load(modelPath: String, backend: Backend) {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = context.cacheDir.path
        )
        val e = Engine(config)
        e.initialize()
        engine = e
        conversation = e.createConversation()
    }

    /**
     * Streams the reply as incremental text deltas.
     *
     * Not marked suspend: the caller collects this and timestamps each emission
     * itself, which is how TTFT and decode rate get measured. Doing the timing
     * in here would mean the numbers include collector-side delays.
     */
    fun chatStream(userMessage: String): Flow<String> {
        val conv = conversation ?: error("Engine not initialized")
        return conv.sendMessageAsync(Contents.of(Content.Text(userMessage)))
            .map { it.toString() }
            .flowOn(Dispatchers.IO)
    }

    /** Fresh conversation, same loaded engine — resets context between runs so
     *  one benchmark's history doesn't inflate the next one's prefill cost. */
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
    }

    private companion object {
        const val TAG = "InferenceEngine"
    }
}
