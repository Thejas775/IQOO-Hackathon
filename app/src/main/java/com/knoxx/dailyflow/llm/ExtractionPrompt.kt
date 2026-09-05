package com.knoxx.dailyflow.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The prompt that turns one captured screen into one memory.
 *
 * Two shapes were possible and the choice matters. Wick asked Gemini for a
 * JSON array covering twenty snippets at once, enforced by a response schema.
 * Neither half of that survives on-device: LiteRT-LM has no schema-constrained
 * decoding, and an unconstrained small model asked for JSON produces trailing
 * commas, smart quotes and unterminated strings often enough that the parser
 * becomes the bug. So this asks for one screen at a time in a flat
 * `KEY: value` format, which degrades gracefully — a malformed line is one
 * missing field, not a discarded batch of twenty.
 */
object ExtractionPrompt {

    /** Captured text is truncated before it reaches the model. A single
     *  screen past this length is almost always a scrolled feed rather than a
     *  conversation, and the cost is paid in prefill on every capture. */
    private const val MAX_SNIPPET_CHARS = 3000

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val isoUtcStamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun build(
        packageName: String,
        capturedAt: Long,
        rawText: String,
        knownPeople: List<String>,
        openCommitments: List<Pair<Long, String>> = emptyList()
    ): String = buildString {
        appendLine(
            "You are DailyFlow, a private memory that runs entirely on one person's phone. " +
                "Call that person \"you\". Below is the text visible on their screen at one " +
                "moment, flattened from the Android accessibility tree — it has no layout, no " +
                "left/right alignment and no speaker labels, only words in roughly reading order."
        )
        appendLine()

        appendLine("SCREEN CAME FROM: $packageName")
        appendLine("CAPTURED AT: ${isoUtcStamp.format(Date(capturedAt))}")
        if (knownPeople.isNotEmpty()) {
            // Given as context rather than as a constraint: it steadies the
            // spelling of recurring names without stopping the model
            // recognising someone new.
            appendLine("PEOPLE ALREADY KNOWN: ${knownPeople.take(20).joinToString(", ")}")
        }
        if (openCommitments.isNotEmpty()) {
            // Numbered rather than given by database id: a small model asked
            // to echo a six-digit id gets a digit wrong often enough to matter,
            // and a wrong id would tick off an unrelated commitment. A short
            // ordinal it can only get right or obviously wrong is safer, and
            // the caller maps it back.
            appendLine()
            appendLine("STILL OPEN (the owner has not finished these yet):")
            openCommitments.forEachIndexed { index, (_, summary) ->
                appendLine("${index + 1}. $summary")
            }
        }
        appendLine()

        appendLine("Decide whether this screen contains something worth remembering later.")
        appendLine(
            "Worth remembering: a promise either side made, a request, a decision, a bill or " +
                "payment due, an order or booking with its date, a work item someone asked for, " +
                "a question awaiting an answer, or a concrete plan."
        )
        appendLine(
            "Not worth remembering: adverts, promotions, app chrome, navigation, notification " +
                "spam, small talk with no content, or a list of messages with nothing new in it."
        )
        appendLine(
            "When genuinely unsure, prefer to record it. A low-value memory costs the owner a " +
                "glance; a missed one costs them the thing itself."
        )
        appendLine()

        appendLine("Reply with EXACTLY these nine lines, in this order, and nothing else:")
        appendLine()
        appendLine("ISLOOP: yes or no")
        appendLine("SUMMARY: one sentence addressed to \"you\", or empty if ISLOOP is no")
        appendLine("QUOTE: the exact sentence from the screen that this is based on, copied word for word, or empty")
        appendLine("DIRECTION: self_promise, they_asked, or from_them")
        appendLine("PERSON: the other person's name, or empty")
        appendLine("CATEGORY: Work, Personal, Home, or Other")
        appendLine("DUE: yyyy-MM-ddTHH:mm:ss if there is a real deadline, or empty")
        appendLine("PROJECT: a named ongoing effort this belongs to, or empty")
        appendLine("COMPLETES: the number of one STILL OPEN item this screen shows is now finished, or empty")
        appendLine()

        appendLine("Rules:")
        appendLine(
            "- QUOTE must be copied character for character from the screen text. Do not tidy, " +
                "shorten or rephrase it. If no single sentence supports the summary, leave QUOTE empty."
        )
        appendLine(
            "- DIRECTION: self_promise when the owner committed to something; they_asked when " +
                "someone asked the owner for something; from_them for information with no " +
                "obligation attached."
        )
        appendLine(
            "- DUE: resolve relative wording like \"tomorrow\" or \"by Friday\" against CAPTURED " +
                "AT above. If only a date is stated with no time, use 00:00:00 and the app will " +
                "apply a sensible hour — never invent a time that was not said. Leave empty when " +
                "there is no real deadline."
        )
        appendLine(
            "- PROJECT: only when the screen names one or clearly continues one. An invented " +
                "project name is worse than none."
        )
        appendLine(
            "- COMPLETES: only when this screen is direct evidence the item is finished — it was " +
                "paid, sent, delivered, confirmed or explicitly called done. Someone merely " +
                "discussing it, promising it again, or asking about it is not completion. Leave " +
                "empty unless you are sure, and never give more than one number."
        )
        appendLine("- If ISLOOP is no, leave SUMMARY, QUOTE, PERSON, DUE and PROJECT empty. COMPLETES is independent: a screen can be worth remembering or not, and still show something was finished.")
        appendLine()

        appendLine("SCREEN TEXT:")
        append(rawText.take(MAX_SNIPPET_CHARS))
    }

    /** Parses the model's DUE line into epoch millis, or null. */
    fun parseDue(raw: String?): Long? {
        val value = raw?.trim()?.trim('"')
        if (value.isNullOrEmpty()) return null
        return try {
            // Parsed in device-local time on purpose: the model resolved
            // "tomorrow" against a wall clock the owner shares, so treating
            // the result as UTC would shift every deadline by the offset.
            synchronized(iso) { iso.parse(value)?.time }
        } catch (e: java.text.ParseException) {
            null
        }
    }
}
