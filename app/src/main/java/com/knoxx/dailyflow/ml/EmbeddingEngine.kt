package com.knoxx.dailyflow.ml

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import com.knoxx.dailyflow.llm.ModelLocator
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device text embeddings, used for dedup and — conditionally — for Ask's
 * retrieval step.
 *
 * MobileBERT is **bundled in the APK** rather than downloaded. The plan called
 * for EmbeddingGemma 300M instead, but that turned out not to be obtainable:
 * the litert-community repo is gated (HTTP 401 GatedRepo, so an in-app
 * download cannot authenticate), and it publishes only raw LiteRT .tflite
 * exports rather than the MediaPipe .task bundle this API needs. Shipping the
 * 26 MB MobileBERT that Wick already proved means embeddings work the instant
 * the app installs, with no download and no gating.
 *
 * A sideloaded [SIDELOAD_FILE] still wins if one is ever present, so a better
 * embedder can be dropped in without a code change.
 */
object EmbeddingEngine {

    private const val TAG = "EmbeddingEngine"

    /** Bundled in app/src/main/assets, so it is always present. */
    private const val BUNDLED_ASSET = "bert_embedder.tflite"

    /** Optional override, sideloaded beside the Gemma model. */
    const val SIDELOAD_FILE = "embedding_gemma.task"

    /** Recorded against every stored vector. Vectors from different models are
     *  not comparable, and cosineSimilarity returns 0f on length mismatch
     *  rather than raising — so without this a model swap would silently score
     *  every old row at zero and quietly erase history from search. */
    private const val BUNDLED_ID = "mobilebert_embedder"
    private const val SIDELOAD_ID = "embedding_gemma_300m"

    private var embedder: TextEmbedder? = null
    private var triedLoading = false

    /**
     * MediaPipe's TextEmbedder is not documented as safe for concurrent
     * embed() calls on one instance. Wick hit this: once dedup and comparison
     * ran together, unrelated content started registering as near-duplicate
     * and 131 of 134 rows in a session were wrongly discarded — consistent
     * with corrupted vectors under concurrent access rather than a strict
     * threshold. Serialising every call is the fix.
     */
    private val mutex = Mutex()

    fun sideloadFile(context: Context): File = File(ModelLocator.modelDir(context), SIDELOAD_FILE)

    private fun hasSideload(context: Context): Boolean =
        sideloadFile(context).let { it.exists() && it.length() > 0 }

    /** Which model will actually be used, decided from the filesystem so it is
     *  answerable before anything has been loaded. */
    fun modelId(context: Context): String =
        if (hasSideload(context)) SIDELOAD_ID else BUNDLED_ID

    /** Embeddings work at all. Always true now that the model ships in the
     *  APK — kept as a function because callers should not assume that. */
    fun isAvailable(context: Context): Boolean = true

    /**
     * Whether embeddings are good enough to *retrieve* on, which is a stricter
     * bar than dedup and deliberately excludes the bundled model.
     *
     * Wick measured MobileBERT scoring two entirely unrelated one-sentence
     * summaries at 0.9975, repeatedly, and abandoned summary-level comparison
     * because of it. Ask compares a short question against short summaries —
     * exactly that case — so ranking by MobileBERT cosine would confidently
     * return the wrong memories. Keyword overlap is cruder but honest, so Ask
     * stays on the fallback until a real retrieval model is sideloaded.
     *
     * Dedup is unaffected: it compares raw screen text against raw screen
     * text, which is long and near-identical when it is a true duplicate, and
     * that is the case MobileBERT handles fine.
     */
    fun supportsRetrieval(context: Context): Boolean = hasSideload(context)

    private fun ensureLoaded(context: Context): TextEmbedder? {
        embedder?.let { return it }
        // Only attempt once per process. Retrying on every capture would log
        // endlessly for a failure that is not going to resolve mid-session.
        if (triedLoading) return null
        triedLoading = true

        return try {
            val base = BaseOptions.builder()
            if (hasSideload(context)) {
                val file = sideloadFile(context)
                // Memory-mapped rather than read onto the heap: MediaPipe
                // requires a direct ByteBuffer, and mapping avoids copying the
                // whole model. Note this is setModelAssetBuffer, not
                // setModelAssetPath — the latter resolves against the assets
                // folder, so passing a filesystem path to it silently fails.
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0L, raf.length())
                    base.setModelAssetBuffer(buffer)
                }
                Log.i(TAG, "Using sideloaded embedder ${file.name}")
            } else {
                base.setModelAssetPath(BUNDLED_ASSET)
                Log.i(TAG, "Using bundled embedder $BUNDLED_ASSET")
            }

            val options = TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(base.build())
                .build()
            TextEmbedder.createFromOptions(context, options).also { embedder = it }
        } catch (t: Throwable) {
            // Non-fatal by design: dedup falls back to exact-text matching and
            // Ask to keyword search, so the app still works.
            Log.e(TAG, "Failed to load embedding model; similarity features disabled", t)
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
