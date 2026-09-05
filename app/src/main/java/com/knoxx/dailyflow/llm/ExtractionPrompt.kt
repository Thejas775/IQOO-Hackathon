package com.knoxx.dailyflow.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The prompt that turns one captured screen into one memory.
 *
 * One screen per inference, answered as flat `KEY: value` lines.
 *
 * Both reasons this file originally gave for that shape were false, and both
 * were checked rather than assumed. LiteRT-LM 0.11.0 does ship constrained
 * decoding — a full tool-calling API plus
 * ExperimentalFlags.enableConversationConstrainedDecoding. And the model does
 * not mangle JSON: measured through the Tuning screen, an unconstrained JSON
 * extraction prompt returned valid JSON 4/4 on a clean input and 6/6 on real
 * captures between 952 and 3,122 characters, matching this format exactly.
 *
 * So JSON and batching were tried on that evidence, and reverted on better
 * evidence. Batching five screens into one array failed differently from
 * batching one: 2 of 5 batches came back unparseable on a real pass, half the
 * rows fell back to single-screen anyway, and 21 rows took 203 seconds —
 * about 9.7s each, indistinguishable from one-at-a-time. The single-screen
 * JSON result did not generalise to a five-object array, and generalising it
 * was the mistake.
 *
 * What survives is the original failure-mode argument, now with numbers behind
 * it: a malformed reply costs one row here, and cost five under batching.
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
            "NOT worth remembering: adverts and offers, promotions, donation appeals, app " +
                "chrome, navigation, notification spam, someone reacting to a message, a photo " +
                "or sticker with no words, and small talk."
        )
        appendLine(
            // Real output from a session that had been told the above and still
            // stored all three. The rule had to be about the *shape* of the
            // sentence rather than the category: "X shared a link" reads like a
            // fact about the day while carrying nothing to act on or recall.
            "In particular, the fact that a message exists is not a memory. \"Utsav just " +
                "messaged you\", \"Prince shared a LinkedIn profile link\" and \"message from " +
                "Amma with a photo\" are all worth nothing: they describe an event without its " +
                "content. Record what was said or asked, not that something arrived. If you " +
                "cannot say what it was about, answer no."
        )
        appendLine(
            "Most screens are worth nothing, and answering no is the common case. Do not write " +
                "a summary to avoid leaving it empty."
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
            // Dates sitting in the text were being left there: "Axis Bank bill
            // 1544.0 due on Sep 2" came back with an empty DUE, so no reminder
            // could ever fire for it. The date has to be lifted out of the
            // sentence, not merely repeated inside it.
            "- If SUMMARY mentions a date or a deadline, DUE must contain it. \"due on Sep 2\", " +
                "\"by Friday\", \"tomorrow at 8:45\" and \"within 25 days\" are all dates: " +
                "resolve each against CAPTURED AT. A summary naming a deadline with an empty " +
                "DUE is wrong."
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

    /**
     * The same task, asked for as JSON. Used only by the Tuning screen's
     * format benchmark — nothing in the pipeline calls this.
     *
     * Deliberately a close mirror of [build]: same instructions, same fields,
     * same truncation, differing only in the shape demanded. A benchmark that
     * also changed the wording would measure the wording.
     */
    fun buildJson(
        packageName: String,
        capturedAt: Long,
        rawText: String
    ): String = buildString {
        appendLine(
            "You are DailyFlow, a private memory that runs entirely on one person's phone. " +
                "Call that person \"you\". Below is the text visible on their screen at one " +
                "moment, flattened from the Android accessibility tree."
        )
        appendLine()
        appendLine("SCREEN CAME FROM: $packageName")
        appendLine("CAPTURED AT: ${isoUtcStamp.format(Date(capturedAt))}")
        appendLine()
        appendLine("Decide whether this screen contains something worth remembering later.")
        appendLine(
            "Reply with ONLY a JSON object, no markdown fence and no other text, matching " +
                "exactly this shape:"
        )
        appendLine(
            "{\"isLoop\":true|false,\"summary\":\"\",\"quote\":\"\",\"direction\":" +
                "\"self_promise|they_asked|from_them\",\"person\":\"\",\"category\":" +
                "\"Work|Personal|Home|Other\",\"due\":\"\",\"project\":\"\"}"
        )
        appendLine("Use an empty string for anything absent. Never write the word empty.")
        appendLine("quote must be copied character for character from the screen text.")
        appendLine("due is yyyy-MM-ddTHH:mm:ss resolved against CAPTURED AT, or an empty string.")
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
