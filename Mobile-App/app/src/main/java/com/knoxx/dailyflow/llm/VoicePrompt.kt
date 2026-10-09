package com.knoxx.dailyflow.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * What to ask Gemma about a spoken note.
 *
 * Deliberately answered in the same block format the screen pipeline uses, so
 * a note said out loud and a message read off a screen produce identical
 * structure and share one parser. A second format would be a second thing to
 * keep correct, and the two would drift.
 *
 * The instructions differ in one way that matters. A captured screen is
 * mostly worth nothing, so that prompt spends its length teaching the model to
 * refuse. A voice note is the opposite: someone deliberately opened the app,
 * pressed record and spoke, so the default should be that every distinct thing
 * they mentioned is worth keeping.
 */
object VoicePrompt {

    private val isoUtcStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(now: Long = System.currentTimeMillis()): String = buildString {
        appendLine(
            "You are DailyFlow, a private memory that runs entirely on one person's phone. " +
                "The audio is a note the owner recorded for themselves. Call them \"you\"."
        )
        appendLine()
        appendLine("RECORDED AT: ${isoUtcStamp.format(Date(now))}")
        appendLine()

        appendLine(
            "Listen to it and pull out every distinct thing they need to do, remember, or " +
                "follow up on. They chose to record this, so assume it matters — do not " +
                "discard something merely because it sounds small."
        )
        appendLine(
            "Give one block for EACH item, separated by a line containing only ---. If the " +
                "note contains nothing to act on, give one block with ISLOOP: no."
        )
        appendLine()

        appendLine("Each block is EXACTLY these lines, in this order, and nothing else:")
        appendLine()
        appendLine("ISLOOP: yes or no")
        appendLine("SUMMARY: one sentence addressed to \"you\", or empty if ISLOOP is no")
        appendLine("QUOTE: empty")
        appendLine("DIRECTION: self_promise, they_asked, or from_them")
        appendLine("PERSON: the person it involves, or empty")
        appendLine("CATEGORY: Work, Personal, Home, or Other")
        appendLine("DUE: yyyy-MM-ddTHH:mm:ss if a time was said, or empty")
        appendLine("PROJECT: a named ongoing effort, or empty")
        appendLine("COMPLETES: empty")
        appendLine()

        appendLine("Rules:")
        appendLine(
            // The screen prompt asks for a verbatim quote and verifies it
            // against the captured text. There is no text to verify against
            // here, so asking for one would invite an invented sentence
            // presented in quotation marks as the owner's own words.
            "- Leave QUOTE empty. There is no written source to copy from, and an invented " +
                "quotation is worse than none."
        )
        appendLine(
            "- DIRECTION: self_promise when they said they would do something; they_asked when " +
                "they are recording that someone asked them; from_them for anything else."
        )
        appendLine(
            "- DUE: resolve \"tomorrow\", \"Friday\" or \"in an hour\" against RECORDED AT. If " +
                "only a day was said, use 00:00:00 and the app applies a sensible hour. Never " +
                "invent a time that was not spoken."
        )
        appendLine(
            "- One block per thing. \"Call the bank and email Priya\" is two items, not one."
        )
        appendLine(
            "- Write what they meant, not what they said. Filler, false starts and repetition " +
                "should not appear in the summary."
        )
        appendLine()
        appendLine("The blocks:")
    }
}
