package com.knoxx.dailyflow.llm

import android.content.Context
import com.knoxx.dailyflow.data.FloatArrayConverter
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ml.EmbeddingEngine
import java.util.Locale
import kotlin.math.sqrt

/**
 * Finds the memories a question is about.
 *
 * Two strategies, chosen by what is actually on the device. With the embedding
 * model present, candidates are ranked by cosine similarity against the
 * question. Without it, retrieval falls back to keyword overlap.
 *
 * The fallback exists because Ask must work on a phone where only the main
 * model was installed. It is genuinely worse — it cannot match "what did I
 * promise Rahul" to a summary phrased "you agreed to send the quotation" —
 * but a weaker answer beats a screen that refuses to answer at all.
 */
object MemorySearch {

    /** How many memories are handed to the model as context. Small on
     *  purpose: every one of these is prefill the user waits through, and a
     *  short, relevant context produces better answers than a long vague one. */
    private const val TOP_K = 8

    /** Below this, a candidate is noise rather than a weak match. Including it
     *  would invite the model to answer from something unrelated. */
    private const val MIN_SIMILARITY = 0.35f

    data class Hit(val memory: MemoryEntity, val score: Float)

    suspend fun search(context: Context, question: String): List<Hit> {
        val candidates = MemoryRepository.retrievalCandidates()
        if (candidates.isEmpty()) return emptyList()

        val queryVector = EmbeddingEngine.embed(context, question)
        return if (queryVector != null) {
            rankByVector(queryVector, candidates)
        } else {
            rankByKeyword(question, candidates)
        }
    }

    private fun rankByVector(query: FloatArray, candidates: List<MemoryEntity>): List<Hit> =
        candidates.asSequence()
            .mapNotNull { memory ->
                // Vectors from a different model are not comparable. Skipping
                // them is why embeddingModel is stored: otherwise a model
                // change silently scores every old row at zero and quietly
                // removes the user's history from search.
                if (memory.embeddingModel != EmbeddingEngine.MODEL_ID) return@mapNotNull null
                val stored = memory.embedding ?: return@mapNotNull null
                Hit(memory, cosine(query, stored))
            }
            .filter { it.score >= MIN_SIMILARITY }
            .sortedByDescending { it.score }
            .take(TOP_K)
            .toList()

    /**
     * Overlap of meaningful words. Deliberately simple — it is a fallback, and
     * anything more elaborate would be reimplementing the embedder badly.
     */
    private fun rankByKeyword(question: String, candidates: List<MemoryEntity>): List<Hit> {
        val terms = tokenise(question)
        if (terms.isEmpty()) return emptyList()

        return candidates.asSequence()
            .map { memory ->
                val haystack = tokenise(
                    listOfNotNull(memory.summary, memory.personName, memory.sourceQuote)
                        .joinToString(" ")
                )
                val overlap = terms.count { it in haystack }
                Hit(memory, overlap.toFloat() / terms.size)
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(TOP_K)
            .toList()
    }

    /** Stopwords removed so "what did I promise Rahul" matches on "promise"
     *  and "rahul" rather than being dominated by "what" and "did". */
    private val STOPWORDS = setOf(
        "the", "a", "an", "is", "are", "was", "were", "do", "did", "does", "i",
        "me", "my", "you", "your", "what", "when", "who", "whom", "which",
        "and", "or", "to", "for", "of", "in", "on", "at", "it", "that", "this",
        "any", "have", "has", "had", "about", "with", "still", "there"
    )

    private fun tokenise(text: String): Set<String> =
        text.lowercase(Locale.US)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .toSet()

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }
}
