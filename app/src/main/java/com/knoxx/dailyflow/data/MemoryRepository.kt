package com.knoxx.dailyflow.data

import android.content.Context
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import kotlin.math.sqrt
import com.knoxx.dailyflow.debug.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** One captured screen, handed over by the accessibility layer. */
data class Capture(
    val timestamp: Long,
    val packageName: String,
    val eventType: String,
    val text: String
)

/** Supplied by the ML layer once it exists, so dedup can compare meaning
 *  rather than only exact text. Null until then — see [setEmbedder]. */
fun interface Embedder {
    suspend fun embed(text: String): FloatArray?
}

/**
 * Bridges capture into storage, and is the single read surface for the UI.
 *
 * Its real job is throwing things away. A day of real use produces thousands
 * of near-identical screens, and every row that survives here costs a Gemma
 * inference and risks showing the user a duplicate card. The gates below run
 * cheapest-first for exactly that reason.
 */
object MemoryRepository {

    private lateinit var dao: MemoryDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var embedder: Embedder? = null
    private var embedderId: String? = null

    /** Fired after a capture survives dedup and is stored. Set by the
     *  Application so this layer never has to know that WorkManager exists. */
    private var onCaptureStored: (() -> Unit)? = null

    /**
     * How far back dedup looks. Generous on purpose: an unread message sitting
     * on screen unchanged for hours should not produce a second memory each
     * time the app is reopened.
     */
    private const val DEDUP_WINDOW_MS = 24 * 60 * 60 * 1000L

    /**
     * How long a screen must sit still before it is worth reading.
     *
     * Wick had already learned this and wrote it down here: it debounced every
     * event type, found that continuous scrolling kept restarting the timer,
     * and measured an 18-minute session producing ~160 captures but only 4
     * rows. That note was sitting directly above this constant when the
     * debounce was widened to every event type anyway, and the result
     * reproduced almost exactly — 11 minutes, 3,046 events, 7 rows.
     *
     * The note was right about the mechanism and wrong about the remedy.
     * Scoping the debounce to text-changed events leaves the real firehose,
     * TYPE_WINDOW_CONTENT_CHANGED, completely unthrottled at ~165 events a
     * minute, which no on-device extractor can drain. The answer is to debounce
     * everything but briefly, so a scroll burst still collapses while a screen
     * glanced at for a moment still lands.
     *
     * Was 3 seconds, which lost almost everything. Measured over eleven
     * minutes of ordinary use: 3,046 events arrived and 86% were cancelled by
     * a later event before the timer ever expired, because every scroll, tap
     * and app switch restarts it from zero. Seven screens survived the whole
     * session, and only two of those held any content — the rest were list
     * screens caught while the user paused to decide where to tap.
     *
     * Scroll bursts fire many times a second, so this is still far longer than
     * needed to collapse one. What it also catches now is a message glanced at
     * for a second and a half, which is how reading actually looks.
     */
    private const val DEBOUNCE_MS = 1_200L
    /** A notification is one discrete event, not a stream of frames, and a
     *  second one from the same app must not cancel the first. */
    private const val NOTIFICATION_EVENT_TYPE = "NOTIFICATION"
    private val pendingByPackage = ConcurrentHashMap<String, Job>()

    /**
     * Cosine threshold for treating a screen as already seen. High on purpose:
     * two genuinely different screens in the same chat app share a great deal
     * of UI chrome, so a lower bar suppresses real content.
     */
    private const val SEMANTIC_THRESHOLD = 0.95f

    /** Bounded so dedup stays O(1) per capture as the table grows all day —
     *  every candidate here is a BLOB that must be deserialised. */
    private const val RECENT_EMBEDDING_SCAN = 200

    /** How many recent screens from the same app the overlap gate compares
     *  against. Text rather than vectors now, so this is a cheap string scan. */
    private const val RECENT_TEXT_SCAN = 40

    /** Above this share of words in common, treat it as the same screen again.
     *  Two captures of one screen differing only by a badge or a ticking
     *  timestamp land near 0.95; two different conversations in the same app
     *  land near 0.45, so there is a wide gap to sit in. */
    private const val OVERLAP_THRESHOLD = 0.80f

    /** How many recent summaries the duplicate check compares against. */
    private const val RECENT_SUMMARY_SCAN = 60

    /** Lower than the capture gate: two summaries of the same thing are
     *  paraphrases, sharing content words while differing in pronouns and
     *  phrasing. The observed duplicate pair scored about 0.6, and two genuinely
     *  different commitments from the same person score far below that. */
    private const val SUMMARY_OVERLAP_THRESHOLD = 0.55f

    /** How many open commitments the extraction prompt is shown. Small: they
     *  are prefill on every captured screen, and a long list makes the model
     *  likelier to match one loosely. */
    private const val OPEN_COMMITMENT_PROMPT_LIMIT = 12

    /** Candidate pool handed to Ask before similarity ranking. */
    private const val RETRIEVAL_POOL = 500

    fun init(context: Context) {
        if (::dao.isInitialized) return
        dao = DailyFlowDatabase.getInstance(context).memoryDao()
    }

    /** Wired up once the on-device embedder is loaded. Until then dedup runs
     *  on exact text alone, which is correct but lets near-duplicates through. */
    fun setEmbedder(id: String, embedder: Embedder) {
        this.embedderId = id
        this.embedder = embedder
    }

    fun setOnCaptureStored(listener: () -> Unit) {
        onCaptureStored = listener
    }

    // ── ingest ───────────────────────────────────────────────────────────

    /** Fire-and-forget: the accessibility callback is not a suspend context
     *  and must not be blocked, so everything happens on the IO dispatcher. */
    fun enqueue(capture: Capture) {
        if (capture.text.isBlank()) return

        // Everything except a notification is debounced per app.
        //
        // Only TYPE_VIEW_TEXT_CHANGED used to be, which missed the actual
        // firehose: TYPE_WINDOW_CONTENT_CHANGED fires on every frame of a
        // scroll. Measured over eight minutes of ordinary use, 1,318 events
        // reached the dedup gates — about 165 a minute. At roughly eight
        // seconds of inference per surviving row, the extractor cannot get
        // near that, so the queue would only ever grow.
        //
        // A screen is worth reading once it settles, not while it moves, so
        // the burst collapses to its last frame. Dedup then becomes a
        // safety net rather than the thing holding back a flood.
        if (capture.eventType == NOTIFICATION_EVENT_TYPE) {
            scope.launch { process(capture) }
        } else {
            pendingByPackage[capture.packageName]?.cancel()
            pendingByPackage[capture.packageName] = scope.launch {
                delay(DEBOUNCE_MS)
                process(capture)
            }
        }
    }

    private suspend fun process(capture: Capture) {
        val since = capture.timestamp - DEDUP_WINDOW_MS
        val preview = capture.text.take(60).replace('\n', ' ')

        // Gate 1 — exact text. Cheapest, and catches the common case of a
        // screen revisited unchanged.
        if (dao.hasRecentExactText(capture.packageName, capture.text, since)) {
            DebugLog.log("capture", "drop exact-dup ${capture.packageName} :: $preview")
            return
        }

        // Gate 2 — token overlap. Catches a screen where the substance is
        // identical but something incidental moved: a new unread badge, a
        // relative timestamp ticking from "3 min" to "5 min".
        //
        // This used to be cosine similarity over MobileBERT embeddings, and it
        // was silently destroying the app. Measured over one real session:
        // 1,152 of 1,901 captures were dropped here, 44 kept. Different
        // people's conversations scored as duplicates of each other — chats
        // with Achan, Amma and Apurva Didi all matched above 0.95 — because
        // every WhatsApp screen shares the same chrome ("Back … Video call
        // Voice call More options"), and that boilerplate is most of the
        // tokens, so it dominated the embedding. The distinctive part, the
        // name and the message, barely moved the vector. It is the same
        // weakness that keeps MobileBERT off retrieval: it cannot tell short,
        // structurally similar texts apart.
        //
        // Jaccard overlap does not have that failure. Shared chrome counts
        // once, and the differing names and content pull the score down hard:
        // the two chat screens above score about 0.43 rather than 0.95.
        val tokens = tokenSet(capture.text)
        if (tokens.isNotEmpty()) {
            val recent = dao.getRecentTexts(capture.packageName, since, RECENT_TEXT_SCAN)
            val closest = recent.maxOfOrNull { jaccard(tokens, tokenSet(it)) } ?: 0f
            if (closest >= OVERLAP_THRESHOLD) {
                DebugLog.log("capture", "drop near-dup ${capture.packageName} overlap=$closest :: $preview")
                return
            }
        }

        // Still computed and stored, though nothing reads it yet: a better
        // embedder can be sideloaded later, and embeddingModel keeps these
        // vectors identifiable as MobileBERT's rather than comparable to it.
        val vector = embedder?.embed(capture.text)

        dao.insert(
            MemoryEntity(
                timestamp = capture.timestamp,
                packageName = capture.packageName,
                rawText = capture.text,
                eventType = capture.eventType,
                embedding = vector,
                embeddingModel = if (vector != null) embedderId else null
            )
        )

        // Logged with the route that produced it, so a gap in one source is
        // visible as a gap rather than as "capture stopped".
        DebugLog.log("capture", "kept ${capture.eventType} ${capture.packageName} :: $preview")

        // Signalled only after a row actually survived both gates, not on
        // every captured event — most captures are dropped, and waking the
        // extractor for each one would keep the model loaded all day for
        // nothing.
        onCaptureStored?.invoke()
    }

    /** Returns 0 on a length mismatch rather than throwing — which is exactly
     *  why [MemoryEntity.embeddingModel] exists. Without it, a model swap
     *  would make every comparison score zero and dedup would silently stop
     *  working rather than fail visibly. */

    /** Words of three characters or more, lowercased and de-duplicated.
     *  Short tokens are dropped because UI chrome is full of them ("to", "PM",
     *  "1") and they inflate the overlap between unrelated screens. */
    private fun tokenSet(text: String): Set<String> =
        text.lowercase(Locale.US)
            .split(NON_WORD)
            .filter { it.length >= 3 }
            .toSet()

    // Raw string: the class escapes are regex syntax, not Kotlin escapes, and
    // writing them singly in a normal string is a compile error.
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Intersection over union. 1.0 is the same words, 0.0 shares none. */
    private fun jaccard(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val shared = a.count { it in b }
        val union = a.size + b.size - shared
        return if (union == 0) 0f else shared.toFloat() / union
    }
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(na) * sqrt(nb)
        return if (denom == 0f) 0f else dot / denom
    }

    /** A task typed by the user. Skips every gate — there is nothing to dedup
     *  against, and it is stored already-understood so it appears immediately. */
    suspend fun addManual(
        title: String,
        dueAt: Long?,
        category: String?,
        personName: String?
    ): Long = dao.insert(
        MemoryEntity(
            timestamp = System.currentTimeMillis(),
            packageName = "",
            rawText = title,
            eventType = "MANUAL",
            extracted = true,
            isLoop = true,
            summary = title,
            direction = Direction.MANUAL.name,
            dueAt = dueAt,
            dueAtExplicit = dueAt != null,
            category = category,
            personName = personName
        )
    )

    /**
     * A second, third or fourth memory found on one captured screen.
     *
     * A chat list is not one thing. A WhatsApp inbox holds ten people's
     * messages at once, and the pipeline used to take one of them and discard
     * the rest — a screen showing Shreya, Nathan and Amma produced a single
     * memory naming whichever the model happened to pick. The capture row
     * carries the first, and each further item becomes its own row so it can
     * have its own person, direction, category and deadline.
     *
     * The raw text is copied from the parent rather than left blank, so quote
     * verification and the dedup gates keep working against the screen these
     * actually came from.
     */
    suspend fun addDerived(
        parent: MemoryEntity,
        summary: String,
        sourceQuote: String?,
        direction: String?,
        dueAt: Long?,
        dueAtExplicit: Boolean,
        personName: String?,
        category: String?,
        projectName: String?
    ): Long = dao.insert(
        MemoryEntity(
            timestamp = parent.timestamp,
            packageName = parent.packageName,
            rawText = parent.rawText,
            eventType = parent.eventType,
            extracted = true,
            isLoop = true,
            summary = summary,
            sourceQuote = sourceQuote,
            direction = direction,
            dueAt = dueAt,
            dueAtExplicit = dueAtExplicit,
            personName = personName,
            category = category,
            projectName = projectName
        )
    )

    // ── pipeline ─────────────────────────────────────────────────────────

    suspend fun pendingExtraction(): List<MemoryEntity> = dao.getPendingExtraction()
    suspend fun recentCaptures(limit: Int) = dao.getRecentCaptures(limit)

    /**
     * Whether this summary says the same thing as one already stored.
     *
     * The capture gates cannot catch this. They compare screen *text*, and two
     * Slack screens showing the same request scrolled differently are genuinely
     * different text — so both survive, both get extracted, and the commitments
     * list shows one obligation twice. Observed exactly that: "Jitendra asked
     * you to provide all the APIs..." and "Jitendra asked Thejas to provide all
     * APIs..." stored as two open commitments.
     *
     * Compared on the summary rather than the screen, because the summary is
     * where the duplication is visible to the user. The threshold is lower than
     * the capture gate's: summaries of the same thing are paraphrases of each
     * other, sharing meaning and most content words while differing in
     * pronouns and article — the pair above scores about 0.6.
     */
    suspend fun hasSimilarSummary(summary: String): Boolean {
        val candidate = tokenSet(summary)
        if (candidate.size < 3) return false
        val since = System.currentTimeMillis() - DEDUP_WINDOW_MS
        return dao.getRecentSummaries(since, RECENT_SUMMARY_SCAN)
            .any { jaccard(candidate, tokenSet(it)) >= SUMMARY_OVERLAP_THRESHOLD }
    }
    suspend fun pendingEmbedding(): List<MemoryEntity> = dao.getPendingEmbedding()
    fun observePendingCount(): Flow<Int> = dao.observePendingExtractionCount()

    suspend fun saveEmbedding(id: Long, vector: FloatArray, model: String) {
        val packed = FloatArrayConverter.fromFloatArray(vector) ?: return
        dao.updateEmbedding(id, packed, model)
    }

    suspend fun saveExtraction(
        id: Long,
        summary: String?,
        sourceQuote: String?,
        direction: Direction?,
        dueAt: Long?,
        dueAtExplicit: Boolean,
        personName: String?,
        category: String?,
        projectName: String?
    ) {
        // isLoop follows from whether a summary was written, not from any
        // separate flag the model produced — measured, small models will
        // write a real summary while flagging it as nothing, contradicting
        // themselves. The presence of the summary is the stronger signal.
        val isLoop = !summary.isNullOrBlank()
        if (!isLoop) {
            dao.markNotAMemory(id)
            return
        }
        dao.updateExtraction(
            id = id,
            isLoop = true,
            summary = summary,
            sourceQuote = sourceQuote,
            direction = (direction ?: Direction.FROM_THEM).name,
            dueAt = dueAt,
            dueAtExplicit = dueAtExplicit,
            personName = personName,
            category = category,
            projectName = projectName
        )
    }

    suspend fun markNotAMemory(id: Long) = dao.markNotAMemory(id)
    suspend fun existingSummaries(): List<String> = dao.getAllSummaries()
    suspend fun recentPeople(limit: Int = 20): List<String> = dao.getRecentPeople(limit)
    suspend fun retrievalCandidates(limit: Int = RETRIEVAL_POOL) = dao.getRetrievalCandidates(limit)
    suspend fun capturedBetween(start: Long, end: Long) = dao.getCapturedBetween(start, end)
    suspend fun byId(id: Long) = dao.getById(id)

    // ── screens ──────────────────────────────────────────────────────────

    fun observeDueBetween(start: Long, end: Long) = dao.observeDueBetween(start, end)
    fun observeCapturedBetween(start: Long, end: Long) = dao.observeCapturedBetween(start, end)
    fun observeCountCapturedBetween(start: Long, end: Long) = dao.observeCountCapturedBetween(start, end)
    fun observeCountDueBetween(start: Long, end: Long) = dao.observeCountDueBetween(start, end)
    fun observeOpenCommitments() = dao.observeOpenCommitments()
    fun observeAllMemories(limit: Int = 300) = dao.observeAllMemories(limit)
    fun observeKeptBetween(start: Long, end: Long) = dao.observeKeptBetween(start, end)
    fun observeMonth(start: Long, end: Long) = dao.observeMonth(start, end)
    fun observeDistinctPeople() = dao.observeDistinctPeople()
    fun observePackageCounts() = dao.observePackageCounts()
    fun observeByPerson(person: String) = dao.observeByPerson(person)
    fun observePersonStats(person: String) = dao.observePersonStats(person)
    fun observeDistinctProjects() = dao.observeDistinctProjects()
    fun observeTotalCaptured() = dao.observeTotalCaptured()
    fun observeMemoryCount() = dao.observeMemoryCount()
    fun observeTimestampsSince(since: Long) = dao.observeTimestampsSince(since)
    fun observeApproximateBytes() = dao.observeApproximateBytes()

    suspend fun setDone(id: Long, done: Boolean) = dao.setCommitmentDone(id, done)
    suspend fun openCommitments(limit: Int = OPEN_COMMITMENT_PROMPT_LIMIT) = dao.getOpenCommitments(limit)
    suspend fun pendingReminders() = dao.getPendingReminders()
    suspend fun markReminderSent(id: Long) = dao.markReminderSent(id)

    /** "Erase all". Also clears the debounce map, so a capture already in
     *  flight cannot land a row immediately after the user wiped everything. */
    suspend fun eraseAll() {
        pendingByPackage.values.forEach { it.cancel() }
        pendingByPackage.clear()
        dao.clearAll()
    }

    // ── day boundaries ───────────────────────────────────────────────────

    /** Device-local midnight-to-midnight for the day [offsetDays] from today.
     *  Used by every screen that says "today", so they all agree on where the
     *  day starts. */
    fun dayBounds(offsetDays: Int = 0): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, offsetDays)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.DAY_OF_YEAR, 1)
        return start to cal.timeInMillis
    }
}
