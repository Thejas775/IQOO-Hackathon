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
    val project: String? = null,

    /** 1-based position in the STILL OPEN list the prompt supplied, when the
     *  screen showed that item was finished. Independent of [isMemory]: a
     *  receipt is often not worth remembering and still completes something. */
    val completesIndex: Int? = null
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

    /**
     * Every memory on one screen, not just the first.
     *
     * A chat list holds several people's messages at once, and taking one of
     * them threw the rest away — the largest source of missed content in the
     * app. The prompt asks for one block per item separated by `---`, and each
     * block is parsed by the same [parse] that handles a single reply, so a
     * malformed block costs one item rather than the screen.
     *
     * Blocks that yield nothing are dropped, except that the first result is
     * always returned even when empty: the caller needs a verdict for the
     * capture row itself, and "nothing here" is a verdict.
     */
    fun parseAll(raw: String?, sourceText: String): List<Extraction> {
        if (raw.isNullOrBlank()) return listOf(Extraction(isMemory = false))

        val blocks = raw.split(BLOCK_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val parsed = blocks.map { parse(it, sourceText) }
        val memories = parsed.filter { it.isMemory || it.completesIndex != null }

        // Deduped within the screen: asked for one block per person, a model
        // will sometimes emit the same item twice under slightly different
        // wording, and both would become separate rows the user has to read.
        val seen = mutableSetOf<String>()
        val unique = memories.filter { seen.add(it.summary.orEmpty().lowercase(Locale.US)) }

        return unique.ifEmpty { listOf(parsed.firstOrNull() ?: Extraction(isMemory = false)) }
    }

    private val BLOCK_SEPARATOR = Regex("(?m)^\\s*-{3,}\\s*$")

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
        // Parsed before the isMemory gate, because a screen that is not worth
        // remembering can still be the evidence that something else is done.
        val completes = fields["COMPLETES"].cleanOrNull()
            ?.filter { it.isDigit() }
            ?.toIntOrNull()
            ?.takeIf { it > 0 }

        val isMemory = summary != null && !describesTheScreen(summary)
        if (!isMemory) return Extraction(isMemory = false, completesIndex = completes)

        val due = ExtractionPrompt.parseDue(fields["DUE"].cleanOrNull())
        // A date with no time parses as midnight. That distinction is the
        // difference between "3pm on Friday" and "sometime Friday", and it
        // has to be captured before the 9 AM anchor below erases it.
        val statedATime = due != null && !isMidnight(due)

        return Extraction(
            isMemory = true,
            summary = summary,
            sourceQuote = verifyQuote(fields["QUOTE"].cleanOrNull(), sourceText),
            direction = Direction.parse(fields["DIRECTION"].cleanOrNull()),
            person = rejectOwner(fields["PERSON"].cleanOrNull()?.take(40), sourceText),
            category = fields["CATEGORY"].cleanOrNull()
                ?.replaceFirstChar { it.uppercase() }
                ?.takeIf { it in CATEGORIES },
            dueAt = due?.let(::applyNineAmIfMidnight),
            dueAtExplicit = statedATime,
            project = fields["PROJECT"].cleanOrNull()?.take(60),
            completesIndex = completes
        )
    }

    /**
     * Drops summaries about the display rather than the owner's life.
     *
     * The prompt asks for this too, but a prompt is a request and this is a
     * guarantee: two of these landed in one day — "The screen shows various
     * unread messages and notifications from different contacts and groups."
     * and "The application is currently initializing." Both cost the owner a
     * row in Memories that tells them nothing, and the second was already
     * untrue by the time the extraction pass read it.
     *
     * Deliberately narrow, because the last blunt noise rule here killed
     * every chat-list screen along with the noise. It fires only on a summary
     * whose grammatical subject is the surface itself, or on a named
     * transient state — never on content that merely mentions an app.
     */
    private fun describesTheScreen(summary: String): Boolean {
        val s = summary.lowercase().trim()
        return SCREEN_SUBJECT.containsMatchIn(s) || TRANSIENT_STATE.containsMatchIn(s)
    }

    /**
     * "The screen shows…", "this page displays…". Anchored at the start so a
     * memory that happens to mention a screen later on survives.
     *
     * Two narrowings, both from running it over real summaries rather than
     * imagined ones:
     *
     * - "list" came out of the noun set. "The list of attendees was shared by
     *   Ravi" is a memory, and the transient-state pattern below already
     *   catches "the list is refreshing" without the help.
     * - The noun has to be followed by a verb. Without that, "the page proofs
     *   are due to the printer on Thursday" matched on "page" alone — a real
     *   deadline thrown away because one word was ambiguous.
     */
    // Raw strings, because a heredoc has eaten a backslash out of this file
    // before and turned a working pattern into a compile error.
    private val SCREEN_SUBJECT = Regex(
        """^(the|this)\s+(screen|page|view|interface|window|display)\s+""" +
            """(is|was|shows|showed|displays|displayed|contains|lists|has|appears|seems)\b"""
    )

    /**
     * States that expire in seconds: "is initializing", "is loading".
     *
     * The verb list is shorter than the obvious one on purpose. "connecting",
     * "updating" and "processing" were in it until they were tested: "you are
     * connecting Priya with the vendor on Monday", "you are updating the deck"
     * and "your refund is processing" are all real memories and all three were
     * being dropped. What is left is the set that takes no object — a thing
     * that is loading is never loading *something* worth remembering.
     */
    private val TRANSIENT_STATE = Regex(
        """\b(is|are|was|currently)\s+(currently\s+)?""" +
            """(initializing|initialising|loading|syncing|buffering|refreshing|starting up)\b"""
    )

    private val KNOWN_KEYS = setOf(
        "ISLOOP", "SUMMARY", "QUOTE", "DIRECTION", "PERSON", "CATEGORY", "DUE", "PROJECT",
        "COMPLETES"
    )

    private fun String?.cleanOrNull(): String? {
        val v = this?.trim()?.trim('"', '\'', '`')?.trim() ?: return null
        return if (v.lowercase(Locale.US) in NULLISH) null else v
    }

    /**
     * Drops the phone's owner when the model names them as the other person.
     *
     * Messaging apps label the owner's own row "Name (You)", and the model
     * reads that as a correspondent — producing "You have an unread message
     * from Thejas regarding your credit card bill", filed against Thejas, on
     * Thejas's own phone. It then propagated into the morning brief, which
     * told the owner they had heard from themselves.
     *
     * Detected from the screen rather than from configuration: the app is
     * never told the owner's name, but the screen that named them almost
     * always marks them, and that marking is the evidence.
     */
    private fun rejectOwner(person: String?, sourceText: String): String? {
        val name = person?.trim() ?: return null
        val needle = name.normalise()
        if (needle.isEmpty()) return null

        // The model answering "me", "you" or "sender" instead of leaving the
        // field empty. Stored literally it produces a Person page called "me",
        // and a card reading "From me" on the owner's own phone.
        if (needle in NOT_A_PERSON) return null

        // Screen furniture promoted to a correspondent. "Compose" and "Inbox"
        // are buttons the capture happened to flatten next to the message, and
        // the model reached for the nearest capitalised word.
        if (needle in UI_LABELS) return null

        // A name marked "(You)" is the owner. Detected from the screen because
        // the app is never told who owns it, and the screen that named them
        // almost always marks them.
        if (OWNER_MARKERS.any { sourceText.normalise().contains("$needle $it") }) return null

        return name
    }

    private val OWNER_MARKERS = listOf("(you)", "(owner)", "· you", "- you")

    private val NOT_A_PERSON = setOf(
        "me", "you", "yourself", "myself", "my self", "self", "i", "us", "we",
        "them", "they", "sender", "recipient", "user", "owner", "the owner",
        "the sender", "unknown sender", "no one", "nobody", "someone", "anyone"
    )

    private val UI_LABELS = setOf(
        "compose", "inbox", "search", "notification", "notifications", "mail",
        "email", "message", "messages", "chat", "chats", "group", "groups",
        "settings", "menu", "home", "back", "more options", "navigation",
        "status", "updates", "calls", "community", "channel", "channels"
    )

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

    /** A date stated with no time of day parses as exactly midnight. That is
     *  the signal for "no time given", and callers need it before the 9 AM
     *  anchor below overwrites it. */
    private fun isMidnight(millis: Long): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) == 0 &&
            cal.get(Calendar.MINUTE) == 0 &&
            cal.get(Calendar.SECOND) == 0
    }

    /**
     * The prompt asks for 00:00:00 when only a date was stated. Left as
     * midnight, every dateless deadline would fire a reminder in the middle of
     * the night, so it is re-anchored to 9 AM local — using the parsed
     * calendar fields directly rather than shifting by an offset, which could
     * move the date itself across a boundary.
     *
     * This is a reminder time, not a known one. dueAtExplicit records that, so
     * the timeline can avoid printing 9 AM as if the user had said it.
     */
    private fun applyNineAmIfMidnight(millis: Long): Long {
        if (!isMidnight(millis)) return millis
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        cal.set(Calendar.HOUR_OF_DAY, 9)
        return cal.timeInMillis
    }
}
