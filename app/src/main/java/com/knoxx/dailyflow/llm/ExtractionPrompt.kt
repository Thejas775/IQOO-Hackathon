package com.knoxx.dailyflow.llm

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The prompt that turns one captured screen into one memory.
 *
 * Two shapes were possible. Wick asked Gemini for a JSON array covering twenty
 * snippets at once, enforced by a response schema. This asks for one screen at
 * a time in a flat `KEY: value` format, which degrades gracefully: a malformed
 * line costs one field, not a discarded batch of twenty.
 *
 * This file previously justified that choice by claiming LiteRT-LM has no
 * schema-constrained decoding. That is **wrong**, and the correction matters
 * because the claim was load-bearing. LiteRT-LM 0.11.0 ships a full
 * tool-calling API — Tool, ToolCall, ToolManager, ToolParam, OpenApiTool,
 * ReflectionTool with JSON schema generation — together with
 * ExperimentalFlags.enableConversationConstrainedDecoding. Structured output
 * with an enforced schema is available. It is @ExperimentalApi and set through
 * a global flag object rather than EngineConfig, but it exists.
 *
 * The other half of the old rationale — that a small model asked for
 * unconstrained JSON emits trailing commas and unterminated strings often
 * enough to make the parser the bug — has now been measured, and it is also
 * wrong for this model. Four runs of an unconstrained JSON extraction prompt
 * on E4B, GPU backend, through the Tuning screen: 4/4 valid JSON, byte
 * identical at 329 characters each, balanced braces, no trailing commas. It
 * resolved "25 days" from a stated June 29 2026 into 2026-07-24 correctly, and
 * echoed a typo in the schema key rather than quietly repairing it. Timings
 * were 29.4s cold including the model load, then 7.6s, 10.1s and 10.1s warm.
 *
 * One real flaw showed up: asked for "name or empty" it returned the literal
 * string "empty" rather than an empty value. The flat format's NULLISH set
 * already absorbs exactly that, and a JSON path would need its own equivalent.
 *
 * So the flat format is a choice, not a necessity, and both reasons originally
 * given for it were false. It is still defensible on its failure mode — one
 * malformed line costs one field, where a malformed batch costs twenty rows —
 * and that is now the only argument for it.
 *
 * That first test repeated one clean prompt, so it proved nothing about messy
 * input. The Tuning screen's format benchmark then ran both formats over six
 * real stored captures — WhatsApp, Gmail and Slack screens between 952 and
 * 3,122 characters of flattened accessibility text, emoji and duplicated
 * labels included. Result: **JSON 6/6, flat 6/6**, twelve inferences in 152
 * seconds. Neither format dropped a single row.
 *
 * So the two formats are equally reliable on this model, and the entire
 * original argument for this one is gone. What remains is a genuine but
 * narrower trade: a malformed reply costs one row here and would cost a whole
 * batch under batching. Since nothing is malformed, that risk is currently
 * theoretical, while the cost of not batching is measured and constant — about
 * ten seconds per captured screen.
 *
 * The reason this has not been changed is scheduling, not evidence. Moving to
 * JSON means rewriting the prompt, the parser and the runner together, days
 * before an evaluation, to fix a throughput problem that the capture debounce
 * has already made much smaller. The benchmark is left in place so the
 * decision can be re-made on numbers whenever there is room for it.
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

    /** How much of each screen a batched prompt carries.
     *
     *  Tighter than [MAX_SNIPPET_CHARS] because five of them share one context
     *  window. Measured on real captures: 770 to 3,120 characters, averaging
     *  1,709 — so this truncates only the longest, and those are scrolled feeds
     *  whose tail is rarely the part that matters. */
    private const val MAX_BATCH_SNIPPET_CHARS = 2200

    /**
     * One prompt covering several screens, answered as a JSON array.
     *
     * Batching is the whole point: at roughly ten seconds per inference, one
     * call per screen is the pipeline's binding constraint. Five screens in one
     * call is not five times cheaper — the prompt is longer and the reply is
     * longer — but it pays the instructions and the model's own warm-up once
     * instead of five times.
     *
     * Screens are numbered and the model is told to echo the number back. That
     * is what makes a short reply survivable: results are matched by their
     * stated index rather than by position, so a batch that returns four
     * objects for five screens loses one row instead of misfiling all five.
     */
    fun buildJsonBatch(
        screens: List<Triple<String, Long, String>>,
        knownPeople: List<String>,
        openCommitments: List<Pair<Long, String>>
    ): String = buildString {
        appendLine(
            "You are DailyFlow, a private memory that runs entirely on one person's phone. " +
                "Call that person \"you\". Below are several screens captured from their phone, " +
                "flattened from the Android accessibility tree — no layout, no speaker labels, " +
                "only words in roughly reading order."
        )
        appendLine()
        if (knownPeople.isNotEmpty()) {
            appendLine("PEOPLE ALREADY KNOWN: ${knownPeople.take(20).joinToString(", ")}")
        }
        if (openCommitments.isNotEmpty()) {
            appendLine("STILL OPEN (the owner has not finished these yet):")
            openCommitments.forEachIndexed { index, (_, summary) ->
                appendLine("${index + 1}. $summary")
            }
        }
        appendLine()

        appendLine("For EACH screen decide whether it contains something worth remembering.")
        appendLine(
            "Worth remembering: a promise either side made, a request, a decision, a bill or " +
                "payment due, an order or booking with its date, a work item someone asked for, " +
                "a question awaiting an answer, or a concrete plan."
        )
        appendLine(
            "NOT worth remembering: adverts and offers, promotions, donation appeals, app " +
                "chrome, navigation, notification spam, someone reacting to a message, a photo " +
                "or sticker with no words, and small talk. For these set isLoop false and leave " +
                "summary empty."
        )
        appendLine(
            // Real output from a batch that had been told the above and still
            // stored all three. The rule needed to be about the *shape* of the
            // sentence, not only the category, because "X shared a link" reads
            // like a fact about the day while carrying nothing to act on or
            // recall.
            "In particular, the fact that a message exists is not a memory. \"Utsav just " +
                "messaged you\", \"Prince shared a LinkedIn profile link\" and \"message from " +
                "Amma with a photo\" are all worth nothing: they describe an event without its " +
                "content. Record what was said or asked, not that something arrived. If you " +
                "cannot say what it was about, set isLoop false."
        )
        appendLine(
            // The single-screen prompt says to record when unsure, which is
            // right there: one doubtful memory costs a glance. In a batch it
            // compounds — the model fills every slot rather than leaving four
            // of five empty, and a real session produced five adverts and
            // "message from Amma with a photo" alongside three real items.
            "Most screens are not worth remembering, and a batch where only one or two have " +
                "anything in them is the normal case. Do not write a summary just to fill the " +
                "array."
        )
        appendLine()

        appendLine(
            "Reply with ONLY a JSON array, no markdown fence and no text around it. One object " +
                "per screen, in this exact shape:"
        )
        appendLine(
            "[{\"screen\":1,\"isLoop\":true,\"summary\":\"\",\"quote\":\"\",\"direction\":" +
                "\"self_promise|they_asked|from_them\",\"person\":\"\",\"category\":" +
                "\"Work|Personal|Home|Other\",\"due\":\"\",\"project\":\"\",\"completes\":0}]"
        )
        appendLine()
        appendLine("Rules:")
        appendLine("- screen must be the number of the screen the object describes.")
        appendLine("- Include one object for every screen, even when isLoop is false.")
        appendLine("- Use an empty string for anything absent. Never write the word empty or null.")
        appendLine(
            "- quote must be copied character for character from that screen's text. If no " +
                "single sentence supports the summary, use an empty string."
        )
        appendLine(
            "- due is yyyy-MM-ddTHH:mm:ss resolved against that screen's CAPTURED AT. If only a " +
                "date is stated, use 00:00:00 and the app will apply a sensible hour. Never " +
                "invent a time that was not said."
        )
        appendLine(
            // Batched replies were dropping dates that were sitting in the
            // text: "Axis Bank bill 1544.0 due on Sep 2" came back with an
            // empty due, so no reminder could ever fire for it. The date has
            // to be lifted out of the sentence, not left inside it.
            "- If the summary mentions a date or a deadline, due MUST contain it. \"due on Sep " +
                "2\", \"by Friday\", \"tomorrow at 8:45\" and \"within 25 days\" are all dates: " +
                "resolve each against CAPTURED AT. A summary naming a deadline with an empty " +
                "due is wrong."
        )
        appendLine(
            "- completes is the number of a STILL OPEN item that this screen shows is now " +
                "finished — paid, sent, delivered or explicitly called done — or 0. Discussing " +
                "it or promising it again is not completion."
        )
        appendLine()

        screens.forEachIndexed { index, (packageName, capturedAt, rawText) ->
            appendLine("=== SCREEN ${index + 1} ===")
            appendLine("FROM: $packageName")
            appendLine("CAPTURED AT: ${isoUtcStamp.format(Date(capturedAt))}")
            appendLine(rawText.take(MAX_BATCH_SNIPPET_CHARS))
            appendLine()
        }

        appendLine("The JSON array:")
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
