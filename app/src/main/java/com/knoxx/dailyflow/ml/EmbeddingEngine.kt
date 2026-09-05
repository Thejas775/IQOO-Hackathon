package com.knoxx.dailyflow.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.knoxx.dailyflow.llm.ModelLocator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device text embeddings, used for dedup and for Ask's retrieval step.
 *
 * The model is **EmbeddingGemma 300M**, not the MobileBERT embedder Wick
 * shipped. That is not an upgrade for its own sake. Wick measured MobileBERT
 * scoring two entirely unrelated one-sentence summaries at 0.9975 similarity,
 * repeatedly, and abandoned summary-level comparison because of it. Ask
 * compares a short question against short summaries — precisely the case that
 * failed — so retrieval simply cannot be built on the old embedder.
 *
 * Optional. If the model file is absent, embedding returns null and callers
 * fall back: dedup drops to exact-text matching, and Ask to keyword search.
 * Degrading is the right behaviour here — the app must still work on a device
 * where only the main model was sideloaded.
 */
object EmbeddingEngine {

    private const val TAG = "EmbeddingEngine"

    /** Sits beside the Gemma model, so one adb push location covers both. */
    const val MODEL_FILE = "embedding_gemma.task"

    /** Recorded against every stored vector. Changing model means changing
     *  this, so old vectors become identifiable rather than silently
     *  incomparable — see MemoryEntity.embeddingModel. */
    const val MODEL_ID = "embedding_gemma_300m"

    private var embedder: TextEmbedder? = null
    private var triedLoading = false

    /**
     * MediaPipe's TextEmbedder is not documented as safe for concurrent embed()
     * calls on one instance. Wick hit this: once dedup and comparison ran
     * together, unrelated content started registering as near-duplicate and
     * 131 of 134 rows in a session were wrongly discarded — consistent with
     * corrupted vectors under concurrent access rather than a strict
     * threshold. Serialising every call is the fix.
     */
    private val mutex = Mutex()

    fun modelFile(context: Context): File = File(ModelLocator.modelDir(context), MODEL_FILE)

    fun isAvailable(context: Context): Boolean = modelFile(context).let { it.exists() && it.length() > 0 }

    private suspend fun ensureLoaded(context: Context): TextEmbedder? {
        embedder?.let { return it }
        // Only attempt once per process. A missing file is not going to appear
        // mid-session, and retrying on every capture would log endlessly.
        if (triedLoading) return null
        triedLoading = true

        val file = modelFile(context)
        if (!file.exists()) {
            Log.i(TAG, "No embedding model at ${file.absolutePath}; similarity features disabled")
            return null
        }

        return try {
            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(file.absolutePath).build())
                .build()
            TextEmbedder.createFromOptions(context, options).also { embedder = it }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load embedding model", t)
            null
        }
    }

    /** Null when the model is unavailable or embedding failed. */
    suspend fun embed(context: Context, text: String): FloatArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val engine = ensureLoaded(context) ?: return@withContext null
            try {
                engine.embed(text)
                    .embeddingResult()
                    .embeddings()
                    .firstOrNull()
                    ?.floatEmbedding()
            } catch (t: Throwable) {
                Log.e(TAG, "Embedding failed", t)
                null
            }
        }
    }

    fun close() {
        embedder?.close()
        embedder = null
        triedLoading = false
    }
}
