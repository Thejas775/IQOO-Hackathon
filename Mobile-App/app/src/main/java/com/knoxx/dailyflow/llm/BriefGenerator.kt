package com.knoxx.dailyflow.llm

import android.content.Context
import com.knoxx.dailyflow.data.Availability
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

    /**
     * Replies to whatever is on screen, for the floating bubble.
     *
     * Separate from [draftFollowUp] because it answers a different question.
     * The first attempt routed this through the extraction prompt and then
     * drafted from the memory that came back, which looked like good reuse and
     * was wrong twice over on the first real test:
     *
     * - Extraction asks "is this worth REMEMBERING?" and will answer no to
     *   plenty that is worth REPLYING to. An email the owner had sent to
     *   themselves came back with nothing at all, because the prompt is told
     *   the owner is not a correspondent — so the bubble had nothing to say
     *   about a message filling the screen.
     * - A screen holds several items and extraction returns a block per item.
     *   Picking among them guessed wrong and answered a portfolio statement
     *   sitting in the list behind an open meeting invitation.
     *
     * Someone who taps the bubble has already decided they want a reply, so
     * the gate is removed and the model is asked the question that was
     * actually meant: which message is the owner looking at, and what should
     * they say back.
     */
    suspend fun draftReplyToScreen(
        context: Context,
        packageName: String,
        screenText: String
    ): String? {
        if (screenText.isBlank()) return null

        val read = GemmaEngine.complete(context, readPrompt(packageName, screenText))
            ?: return null

        val fields = read.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf(':')
                if (at <= 0) null
                else line.substring(0, at).trim().uppercase() to line.substring(at + 1).trim()
            }
            .toMap()

        val message = fields["MESSAGE"]?.takeIf { it.isNotBlank() && !it.equals("none", true) }
            ?: return null

        val proposed = ExtractionPrompt.parseDue(fields["TIME"])
        val availability = proposed?.let { DeviceCalendar.availabilityAt(context, it) }

        return GemmaEngine.complete(
            context,
            replyPrompt(
                message = message,
                from = fields["FROM"]?.takeIf { it.isNotBlank() && !it.equals("none", true) },
                proposed = proposed,
                availability = availability
            )
        )?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    /**
     * Pass one: find the message, ignore the furniture.
     *
     * Told explicitly to ignore the app's own suggested replies. Gmail renders
     * "Yes, I am available." as tappable chips right under the message, and
     * they read to a model like someone's answer — it would otherwise reply to
     * a button.
     */
    private fun readPrompt(packageName: String, screenText: String) = buildString {
        appendLine(
            "Below is the text visible on one screen of $packageName, flattened from the " +
                "Android accessibility tree. The phone's owner is looking at it and wants to " +
                "reply to it."
        )
        appendLine()
        appendLine("Find the most recent message that is addressed to the owner.")
        appendLine()
        appendLine("Answer with EXACTLY these three lines and nothing else:")
        appendLine("MESSAGE: what they said, in their own words, or none")
        appendLine("FROM: who sent it, or none")
        appendLine("TIME: yyyy-MM-ddTHH:mm:ss if the message proposes a specific time, or empty")
        appendLine()
        appendLine("Rules:")
        appendLine(
            "- Ignore buttons, menus, tabs and the app's own suggested replies. Phrases like " +
                "\"Yes, I am available.\" offered as options are not messages."
        )
        appendLine(
            "- MESSAGE is what was actually written. Do not summarise it away and do not " +
                "invent anything that is not on the screen."
        )
        appendLine(
            "- TIME: resolve \"tomorrow at 5pm\" or \"Friday 3\" against NOW below. Leave it " +
                "empty unless a specific time is proposed."
        )
        appendLine()
        appendLine("NOW: ${isoLocal.format(Date())}")
        appendLine()
        appendLine("SCREEN:")
        appendLine(screenText.take(3000))
        appendLine()
        appendLine("The three lines:")
    }

    /** Pass two: say it. Same availability rules as [draftFollowUp]. */
    private fun replyPrompt(
        message: String,
        from: String?,
        proposed: Long?,
        availability: Availability?
    ) = buildString {
        appendLine("Write the short reply the phone's owner can send to this message.")
        appendLine()
        appendLine("THEY SAID: \"$message\"")
        from?.let { appendLine("FROM: $it") }
        proposed?.let { appendLine("TIME BEING PROPOSED: ${readableTime.format(Date(it))}") }

        if (availability != null && availability.known) {
            appendLine()
            if (availability.free) {
                appendLine("YOUR CALENDAR: nothing else booked then. You are free.")
            } else {
                appendLine("YOUR CALENDAR: you are already busy then.")
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
        appendLine("- Answer what they actually asked. Warm and direct, no corporate phrasing.")
        if (availability != null && availability.known) {
            appendLine(
                if (availability.free) {
                    "- The time works. Say yes and confirm the time back to them."
                } else {
                    "- The time does not work. Say you are not free then and offer one of the " +
                        "times under YOU ARE FREE AT. Offer no other time."
                }
            )
            appendLine("- Never say what the other commitment is. \"I'm tied up then\" is enough.")
        } else if (proposed != null) {
            appendLine(
                "- Do not say whether you are free or busy, and do not accept or decline the " +
                    "time. Nothing above tells you which is true."
            )
        }
        appendLine("- Output only the message itself, with no quotes or labels around it.")
        appendLine()
        appendLine("The reply:")
    }

    /** "3:00 PM on Thu 16 Oct" — a time a person would say out loud. */
    private val readableTime =
        SimpleDateFormat("h:mm a 'on' EEE d MMM", Locale.getDefault())

    /** The format the date parser expects back. */
    private val isoLocal = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

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
