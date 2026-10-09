package com.knoxx.dailyflow.llm

import android.content.Context
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.ml.EmbeddingEngine
import java.util.Locale
import kotlin.math.sqrt

/**
 * Finds the memories a question is about.
 *
 * Two strategies, chosen by how good the installed embedder is. Ranking by
 * cosine similarity needs a model that can tell two short summaries apart,
 * and the bundled MobileBERT demonstrably cannot — Wick measured it scoring
 * unrelated one-sentence summaries at 0.9975. So the vector path is used only
 * when a real retrieval model has been sideloaded, and otherwise retrieval
 * falls back to keyword overlap.
 *
 * The fallback is genuinely worse — it cannot match "what did I promise
 * Rahul" to a summary phrased "you agreed to send the quotation" — but a
 * weaker answer beats a confidently wrong one, which is what ranking on a
 * model that scores everything at 0.99 would produce.
 */
object MemorySearch {

    /** How many memories are handed to the model as context. Small on
     *  purpose: every one of these is prefill the user waits through, and a
     *  short, relevant context produces better answers than a long vague one. */
    private const val TOP_K = 8

    /** Below this, a candidate is noise rather than a weak match. Including it
     *  would invite the model to answer from something unrelated. */
    private const val MIN_SIMILARITY = 0.35f

    /** How many recent memories to show the model when nothing matched.
     *  Smaller than TOP_K: unmatched context is a weaker signal, and a long
     *  list of irrelevant memories invites the model to answer from one. */
    private const val RECENT_FALLBACK = 4

    data class Hit(val memory: MemoryEntity, val score: Float)

    suspend fun search(context: Context, question: String): List<Hit> {
        val candidates = MemoryRepository.retrievalCandidates()
        if (candidates.isEmpty()) return emptyList()

        // Only when the embedder is good enough to rank on. The bundled
        // MobileBERT is not — see EmbeddingEngine.supportsRetrieval.
        val queryVector = if (EmbeddingEngine.supportsRetrieval(context)) {
            EmbeddingEngine.embed(context, question)
        } else {
            null
        }
        val ranked = if (queryVector != null) {
            rankByVector(queryVector, EmbeddingEngine.modelId(context), candidates)
        } else {
            rankByKeyword(question, candidates)
        }

        // Nothing matched, so fall back to the most recent memories rather
        // than handing the model an empty list.
        //
        // An empty list makes it refuse, which is right for "what did I
        // promise Rahul" when Rahul was never mentioned, but wrong for "hi"
        // and for anything phrased differently from the summary it is
        // actually about. Keyword overlap misses constantly at this scale:
        // "what's still open?" reduces to the single term "open" once
        // stopwords are removed, and matches nothing unless a summary happens
        // to contain that exact word. Greetings reduce to nothing at all,
        // since tokenise() drops words of two characters or fewer.
        //
        // Scored 0f to mark them as context rather than matches, so the
        // caller can tell the difference and not cite them as sources.
        return ranked.ifEmpty { candidates.take(RECENT_FALLBACK).map { Hit(it, 0f) } }
    }

    private fun rankByVector(
        query: FloatArray,
        modelId: String,
        candidates: List<MemoryEntity>
    ): List<Hit> =
        candidates.asSequence()
            .mapNotNull { memory ->
                // Vectors from a different model are not comparable. Skipping
                // them is why embeddingModel is stored: otherwise a model
                // change silently scores every old row at zero and quietly
                // removes the user's history from search.
                if (memory.embeddingModel != modelId) return@mapNotNull null
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
                val overlap = terms.count { term -> matches(term, haystack) }
                Hit(memory, overlap.toFloat() / terms.size)
            }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(TOP_K)
            .toList()
    }

    /**
     * Whether a question word appears in a memory, allowing for word endings.
     *
     * Exact matching made the fallback much weaker than it looks: "what did
     * Jitendra ask" does not match "Jitendra asked you", and "what did I
     * promise" does not match "you promised". Nearly every question about the
     * past is phrased in a different tense from the memory of it.
     *
     * Prefix matching in either direction handles that without a stemmer.
     * Restricted to words of four characters or more, because below that the
     * prefixes are too generic — "car" would match "card" and "care", and at
     * that length a false match is more likely than a real one.
     */
    private fun matches(term: String, haystack: Set<String>): Boolean {
        if (term in haystack) return true
        if (term.length < 4) return false
        return haystack.any { it.length >= 4 && (it.startsWith(term) || term.startsWith(it)) }
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
