package com.knoxx.dailyflow.data

import android.content.Context
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
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
     * Collapses a burst of keystroke events into one insert of the final
     * state. Scoped to text-changed events only — Wick applied this to every
     * event type and found continuous scrolling kept cancelling and
     * restarting the timer, so almost nothing survived to be processed: an
     * 18-minute session produced ~160 captures but only 4 rows. Deciding
     * "is this new" is the dedup gates' job, not an arbitrary wait.
     */
    private const val DEBOUNCE_MS = 3_000L
    private const val DEBOUNCED_EVENT_TYPE = "TYPE_VIEW_TEXT_CHANGED"
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

        if (capture.eventType == DEBOUNCED_EVENT_TYPE) {
            pendingByPackage[capture.packageName]?.cancel()
            pendingByPackage[capture.packageName] = scope.launch {
                delay(DEBOUNCE_MS)
                process(capture)
            }
        } else {
            scope.launch { process(capture) }
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

        // Gate 2 — meaning. Catches a screen where the substance is identical
        // but something incidental moved: a new unread badge, a relative
        // timestamp ticking from "3 min" to "5 min". Exact match cannot see
        // those; they are different strings describing the same moment.
        val vector = embedder?.embed(capture.text)
        if (vector != null) {
            val recent = dao.getRecentEmbeddings(capture.packageName, since, RECENT_EMBEDDING_SCAN)
            val closest = recent.maxOfOrNull { bytes ->
                val other = FloatArrayConverter.toFloatArray(bytes)
                if (other == null) 0f else cosineSimilarity(vector, other)
            } ?: 0f
            if (closest >= SEMANTIC_THRESHOLD) {
                DebugLog.log("capture", "drop near-dup ${capture.packageName} sim=$closest :: $preview")
                return
            }
        }

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

    // ── pipeline ─────────────────────────────────────────────────────────

    suspend fun pendingExtraction(): List<MemoryEntity> = dao.getPendingExtraction()
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
    fun observeCountDueBetween(start: Long, end: Long) = dao.observeCountDueBetween(start, end)
    fun observeOpenCommitments() = dao.observeOpenCommitments()
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
