package com.knoxx.dailyflow.llm

import android.content.Context
import com.knoxx.dailyflow.data.MemoryEntity

/**
 * The one-line morning brief, and the draft follow-up.
 *
 * Both are short, interpretive pieces of writing over memories the app has
 * already understood — no retrieval, no new facts. Kept together because they
 * share the same constraint: say only what the listed memories support.
 */
object BriefGenerator {

    /**
     * Synthesises the day into a single sentence for the hero card.
     *
     * Returns null rather than an apology when it cannot be produced. The
     * caller already has a factual fallback derived from the counts, and
     * showing "I couldn't generate a brief" in the largest text on the home
     * screen would be worse than quietly showing something true.
     */
    /**
     * The line on a Person page, in Gemma's words.
     *
     * The card carrying it is labelled "GEMMA · OFFLINE RECALL" and until now
     * nothing ever set it — it always showed a sentence derived from the open
     * and kept counts, under a heading claiming the model wrote it. On an app
     * whose whole argument is that its claims are literally true, a label that
     * credits a model that never ran is the one kind of wrong worth fixing
     * before anything cosmetic.
     */
    suspend fun relationshipLine(
        context: Context,
        person: String,
        memories: List<MemoryEntity>
    ): String? {
        if (memories.isEmpty()) return null

        val prompt = buildString {
            appendLine(
                "You are DailyFlow, a private memory on one person's phone. Below is everything " +
                    "the owner's phone has recorded involving $person. Write ONE sentence, " +
                    "addressed to \"you\", describing where things stand between them."
            )
            appendLine()
            appendLine("Rules:")
            appendLine("- Use only what is listed. Never invent a conversation or a date.")
            appendLine("- Say what is outstanding, or that nothing is.")
            appendLine("- No greeting, no preamble, no list. Just the sentence.")
            appendLine()
            appendLine("WITH $person:")
            memories.take(12).forEach { memory ->
                val state = if (memory.commitmentDone) "done" else "open"
                appendLine("- [$state] ${memory.summary}")
            }
            appendLine()
            appendLine("The sentence:")
        }

        return GemmaEngine.complete(context, prompt)
            ?.let(::trimToSummary)
            ?.takeIf { it.isNotBlank() }
    }

    suspend fun morningBrief(context: Context, todays: List<MemoryEntity>): String? {
        if (todays.isEmpty()) return null

        val prompt = buildString {
            appendLine(
                "You are DailyFlow, a private memory on one person's phone. Below is everything " +
                    "from their day. Write a short summary of the day, addressed to \"you\"."
            )
            appendLine()
            appendLine("Rules:")
            appendLine("- Use only what is listed. Never invent a person, task or deadline.")
            appendLine("- Two or three sentences. Cover the day as a whole.")
            appendLine("- Mention what is still open, but do not make the whole summary about it.")
            appendLine("- No greeting, no preamble, no list. Just the summary.")
            appendLine(
                "- Plain and specific. Name the person or the thing rather than saying " +
                    "\"a few items\"."
            )
            appendLine()
            appendLine("TODAY:")
            todays.take(15).forEach { memory ->
                val state = if (memory.commitmentDone) "done" else "open"
                val who = memory.personName?.let { " (with $it)" }.orEmpty()
                appendLine("- [$state]$who ${memory.summary}")
            }
            appendLine()
            appendLine("The summary:")
        }

        return GemmaEngine.complete(context, prompt)
            ?.let(::trimToSummary)
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Drafts a message chasing up an open commitment.
     *
     * Deliberately never sent, only shown for the user to copy. A memory app
     * that can send messages on someone's behalf is a different and far more
     * dangerous product, and the gap between "drafted" and "sent" is the whole
     * safety margin.
     */
    suspend fun draftFollowUp(context: Context, memory: MemoryEntity): String? {
        val summary = memory.summary ?: return null

        val prompt = buildString {
            appendLine(
                "Write a short, friendly message the phone's owner can send about this " +
                    "outstanding commitment."
            )
            appendLine()
            appendLine("COMMITMENT: $summary")
            memory.personName?.let { appendLine("TO: $it") }
            memory.sourceQuote?.let { appendLine("ORIGINALLY SAID: \"$it\"") }
            appendLine()
            appendLine("Rules:")
            appendLine("- Two sentences at most. Write it as the owner, ready to send.")
            appendLine("- Warm and direct. No apologising at length, no corporate phrasing.")
            appendLine("- Do not invent a new date or promise anything that isn't above.")
            appendLine("- Output only the message itself, with no quotes or labels around it.")
            appendLine()
            appendLine("The message:")
        }

        return GemmaEngine.complete(context, prompt)
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Small models answer a request for one sentence with a paragraph often
     * enough to matter, and the brief card has room for one line. Trimming
     * here beats letting the card overflow or clip mid-word.
     */
    private fun trimToSummary(raw: String): String {
        val cleaned = raw.trim().removePrefix("\"").removeSuffix("\"").trim()
        // Three sentences are allowed: the card is a summary of the whole day,
        // not a single headline. A fourth is where a model starts padding.
        val parts = Regex("(?<=[.!?])\\s+").split(cleaned)
        return parts.take(3).joinToString(" ").trim()
    }
}
