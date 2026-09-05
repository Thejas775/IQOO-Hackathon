package com.knoxx.dailyflow.llm

import com.knoxx.dailyflow.data.Direction
import java.util.Calendar
import java.util.Locale

/** What one capture yielded. All fields optional except [isMemory]. */
data class Extraction(
    val isMemory: Boolean,
    val summary: String? = null,
    val sourceQuote: String? = null,
    val direction: Direction = Direction.FROM_THEM,
    val person: String? = null,
    val category: String? = null,
    val dueAt: Long? = null,
    val dueAtExplicit: Boolean = false,
    val project: String? = null
)

/**
 * Turns the model's eight-line reply into an [Extraction].
 *
 * Written to be forgiving in shape and strict in content. A small model
 * reliably produces the right *keys* but wraps values in quotes, adds
 * markdown asterisks, writes "None" instead of leaving a field empty, or
 * prefixes the block with "Here you go:". None of that should lose the whole
 * capture, so parsing tolerates all of it — while the two fields that could
 * mislead the user, the quote and the summary, are checked rather than trusted.
 */
object ExtractionParser {

    /** Values that mean "empty" in practice. Small models say these instead of
     *  leaving the field blank, and storing them literally would show the user
     *  a commitment assigned to a person called "None". */
    private val NULLISH = setOf(
        "", "-", "none", "n/a", "na", "null", "nil", "empty", "unknown",
        "not specified", "not applicable", "no", "nothing"
    )

    private val CATEGORIES = setOf("Work", "Personal", "Home", "Other")

    fun parse(raw: String?, sourceText: String): Extraction {
        if (raw.isNullOrBlank()) return Extraction(isMemory = false)

        val fields = mutableMapOf<String, String>()
        raw.lineSequence().forEach { line ->
            // Strip markdown emphasis and list bullets the model sometimes
            // wraps the keys in, then split on the first colon only — values
            // legitimately contain colons ("meet at 3:00").
            val cleaned = line.trim().removePrefix("-").removePrefix("*").trim()
                .replace("**", "")
            val idx = cleaned.indexOf(':')
            if (idx <= 0) return@forEach
            val key = cleaned.substring(0, idx).trim().uppercase(Locale.US)
            if (key !in KNOWN_KEYS) return@forEach
            fields[key] = cleaned.substring(idx + 1).trim().trim('"', '\'', '`')
        }

        val summary = fields["SUMMARY"].cleanOrNull()

        // isMemory follows from whether a summary was actually written, not
        // from ISLOOP. Measured on Gemini and true of small models generally:
        // they will write a well-formed, on-topic summary and still answer
        // "no" on the flag, contradicting themselves. Producing the sentence
        // is the stronger evidence of intent than the boolean beside it.
        val isMemory = summary != null
        if (!isMemory) return Extraction(isMemory = false)

        val due = ExtractionPrompt.parseDue(fields["DUE"].cleanOrNull())

        return Extraction(
            isMemory = true,
            summary = summary,
            sourceQuote = verifyQuote(fields["QUOTE"].cleanOrNull(), sourceText),
            direction = Direction.parse(fields["DIRECTION"].cleanOrNull()),
            person = fields["PERSON"].cleanOrNull()?.take(40),
            category = fields["CATEGORY"].cleanOrNull()
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it in CATEGORIES },
            dueAt = due?.let(::applyNineAmIfMidnight),
            dueAtExplicit = due != null,
            project = fields["PROJECT"].cleanOrNull()?.take(60)
        )
    }

    private val KNOWN_KEYS = setOf(
        "ISLOOP", "SUMMARY", "QUOTE", "DIRECTION", "PERSON", "CATEGORY", "DUE", "PROJECT"
    )

    private fun String?.cleanOrNull(): String? {
        val v = this?.trim()?.trim('"', '\'', '`')?.trim() ?: return null
        return if (v.lowercase(Locale.US) in NULLISH) null else v
    }

    /**
     * A quote is only kept if it genuinely appears in the captured text.
     *
     * Asked to copy a sentence verbatim, a small model will often paraphrase
     * it instead — and a paraphrase displayed in quotation marks is the app
     * putting words in someone's mouth. On a product whose entire claim is a
     * trustworthy record, showing no evidence is much better than showing
     * invented evidence, so an unverifiable quote is dropped rather than shown.
     *
     * Comparison is whitespace-normalised and case-insensitive, since the
     * capture flattens a view tree and its spacing is arbitrary.
     */
    private fun verifyQuote(quote: String?, sourceText: String): String? {
        val candidate = quote?.trim()?.trim('"', '“', '”') ?: return null
        if (candidate.length < 8) return null

        val haystack = sourceText.normalise()
        val needle = candidate.normalise()
        return if (needle.isNotEmpty() && haystack.contains(needle)) candidate else null
    }

    private fun String.normalise(): String =
        lowercase(Locale.US).replace(WHITESPACE, " ").trim()

    private val WHITESPACE = Regex("\\s+")

    /**
     * The prompt asks for 00:00:00 when only a date was stated. Left as
     * midnight, every dateless deadline would fire a reminder in the middle of
     * the night, so it is re-anchored to 9 AM local — using the parsed
     * calendar fields directly rather than shifting by an offset, which could
     * move the date itself across a boundary.
     */
    private fun applyNineAmIfMidnight(millis: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val isMidnight = cal.get(Calendar.HOUR_OF_DAY) == 0 &&
            cal.get(Calendar.MINUTE) == 0 &&
            cal.get(Calendar.SECOND) == 0
        if (!isMidnight) return millis
        cal.set(Calendar.HOUR_OF_DAY, 9)
        return cal.timeInMillis
    }
}
