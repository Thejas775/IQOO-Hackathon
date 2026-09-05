package com.knoxx.dailyflow.llm

import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.ui.DateLabels

/**
 * Builds the question put to Gemma, and the citation shown under its answer.
 *
 * The prompt is written to make the model refuse rather than guess. A memory
 * app that confabulates is worse than one that says it does not know: the
 * whole value is that the user can trust what it tells them about their own
 * life, and a confident wrong answer destroys that in a way a blank one does
 * not.
 */
object AskPrompt {

    fun build(question: String, hits: List<MemorySearch.Hit>): String = buildString {
        appendLine(
            "You are DailyFlow, a private memory running on one person's phone. Answer their " +
                "question using only the memories listed below. Address them as \"you\"."
        )
        appendLine()
        appendLine("Rules:")
        appendLine("- Use only what the memories say. Do not add facts, names or dates that are not there.")
        appendLine(
            "- If the memories do not answer the question, say so plainly in one sentence. Do not " +
                "guess, and do not offer a maybe."
        )
        appendLine("- Answer in one to three sentences. No lists, no headings, no preamble.")
        appendLine("- Refer to people by the names used in the memories.")
        appendLine()

        if (hits.isEmpty()) {
            // Still asked, rather than short-circuited in code, so the refusal
            // is phrased in the model's own voice and matches the tone of
            // every other answer.
            appendLine("MEMORIES: none recorded that relate to this question.")
        } else {
            appendLine("MEMORIES:")
            hits.forEachIndexed { index, hit ->
                val m = hit.memory
                val when_ = DateLabels.relativeShort(m.timestamp)
                val who = m.personName?.let { ", with $it" } .orEmpty()
                appendLine("${index + 1}. [$when_$who] ${m.summary}")
                // The verbatim line is included when it exists: it is the only
                // part that is the user's own words, so it grounds the answer
                // better than the app's paraphrase of it.
                m.sourceQuote?.let { appendLine("   exact words: \"$it\"") }
            }
        }

        appendLine()
        appendLine("QUESTION: $question")
    }

    /**
     * "Source · Slack, 3d" — where the answer came from.
     *
     * Built from the retrieved rows rather than asked of the model, because a
     * citation the model wrote could be invented. This one is a fact about
     * which records were actually used.
     */
    fun citation(hits: List<MemorySearch.Hit>): String? {
        if (hits.isEmpty()) return null
        val apps = hits.mapNotNull { hit ->
            hit.memory.packageName.takeIf { it.isNotBlank() }
        }.distinct().take(3).map { com.knoxx.dailyflow.ui.sourceAppFor(it).label }

        val newest = hits.maxOfOrNull { it.memory.timestamp }
        val where = if (apps.isEmpty()) "your own notes" else apps.joinToString(", ")
        val when_ = newest?.let { ", ${DateLabels.relativeShort(it)}" }.orEmpty()
        return "Source · $where$when_"
    }
}
