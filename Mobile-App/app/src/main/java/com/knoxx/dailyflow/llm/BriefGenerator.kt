package com.knoxx.dailyflow.llm

import android.content.Context
import com.knoxx.dailyflow.data.DeviceCalendar
import com.knoxx.dailyflow.data.MemoryEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // The calendar is only consulted when a specific time was actually
        // stated. dueAtExplicit is the difference between "3pm Thursday" and
        // a date-only deadline the app anchored to 9 AM itself — checking
        // availability against an hour nobody proposed would invent a clash.
        val proposed = memory.dueAt?.takeIf { memory.dueAtExplicit }
        val availability = proposed?.let { DeviceCalendar.availabilityAt(context, it) }

        val prompt = buildString {
            appendLine(
                "Write a short, friendly message the phone's owner can send in reply to this."
            )
            appendLine()
            appendLine("ABOUT: $summary")
            memory.personName?.let { appendLine("TO: $it") }
            memory.sourceQuote?.let { appendLine("THEY SAID: \"$it\"") }
            proposed?.let { appendLine("TIME BEING PROPOSED: ${readableTime.format(Date(it))}") }

            // The whole point of the feature: a reply to "can you make 3?"
            // that does not know whether 3 is free is just a politely worded
            // guess, and the owner has to go and check the calendar anyway —
            // which is the work the draft was supposed to save.
            if (availability != null && availability.known) {
                appendLine()
                if (availability.free) {
                    appendLine("YOUR CALENDAR: nothing else booked at that time. You are free.")
                } else {
                    appendLine(
                        "YOUR CALENDAR: you are already busy then — " +
                            availability.conflicts.size.let { n ->
                                if (n == 1) "one thing is booked" else "$n things are booked"
                            } + " over that time."
                    )
                    if (availability.alternatives.isNotEmpty()) {
                        appendLine(
                            "YOU ARE FREE AT: " + availability.alternatives.joinToString(" or ") {
                                readableTime.format(Date(it))
                            }
                        )
                    }
                }
            }

            appendLine()
            appendLine("Rules:")
            appendLine("- Two sentences at most. Write it as the owner, ready to send.")
            appendLine("- Warm and direct. No apologising at length, no corporate phrasing.")
            if (availability != null && availability.known) {
                appendLine(
                    if (availability.free) {
                        "- The time works. Accept it plainly and confirm the time back to them."
                    } else {
                        "- The time does not work. Say you are not free then, and offer one of " +
                            "the times under YOU ARE FREE AT. Offer no other time."
                    }
                )
                appendLine(
                    // The draft is going to another person. What else is on
                    // the owner's calendar is nobody's business, and a model
                    // handed titles will helpfully paste one in.
                    "- Never say what the other commitment is. \"I'm tied up then\" is enough."
                )
            } else {
                appendLine(
                    // Silence beats a guess: an unreadable calendar and an
                    // empty one look identical from here.
                    "- Do not say whether you are free or busy, and do not accept or decline a " +
                        "time. Nothing above tells you which is true."
                )
            }
            appendLine("- Do not invent a date or promise anything that isn't above.")
            appendLine("- Output only the message itself, with no quotes or labels around it.")
            appendLine()
            appendLine("The message:")
        }

        return GemmaEngine.complete(context, prompt)
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }
    }

    /** "3:00 PM on Thu 16 Oct" — a time a person would say out loud. */
    private val readableTime =
        SimpleDateFormat("h:mm a 'on' EEE d MMM", Locale.getDefault())

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
