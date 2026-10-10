package com.knoxx.dailyflow.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Every read and write against the memory store.
 *
 * Updates are column-scoped `@Query`s rather than `@Update` on the whole row.
 * Extraction and embedding run independently and can interleave on the same
 * row; a full-row replace from a stale in-memory copy would silently clobber
 * whichever column the other one just wrote.
 */
@Dao
interface MemoryDao {

    @Insert
    suspend fun insert(memory: MemoryEntity): Long

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    // ── pipeline queues ──────────────────────────────────────────────────

    @Query("SELECT * FROM memories WHERE embedding IS NULL ORDER BY timestamp ASC")
    suspend fun getPendingEmbedding(): List<MemoryEntity>

    /** Oldest first, so a backlog drains in the order things actually
     *  happened — a memory arriving out of order reads as a bug to the user. */
    @Query("SELECT * FROM memories WHERE extracted = 0 ORDER BY timestamp ASC")
    suspend fun getPendingExtraction(): List<MemoryEntity>

    /** Recent captures regardless of extraction state, for the Tuning screen's
     *  format benchmark. Raw text is what a format has to survive, so the
     *  benchmark has to run on the real messy thing rather than a clean
     *  example typed by hand. */
    @Query("SELECT * FROM memories ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentCaptures(limit: Int): List<MemoryEntity>

    /** Recent summaries, for the post-extraction duplicate check. Text only —
     *  the whole rows would be wasted work for a string comparison. */
    @Query(
        "SELECT summary FROM memories WHERE summary IS NOT NULL " +
            "AND timestamp > :since ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRecentSummaries(since: Long, limit: Int): List<String>

    @Query("SELECT COUNT(*) FROM memories WHERE extracted = 0")
    fun observePendingExtractionCount(): Flow<Int>

    /**
     * Takes a pre-packed ByteArray, not a FloatArray: Room expands a raw
     * FloatArray parameter into one bind argument per element instead of
     * routing it through FloatArrayConverter, which produces invalid SQL.
     */
    @Query("UPDATE memories SET embedding = :embedding, embeddingModel = :model WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray, model: String)

    @Query(
        "UPDATE memories SET extracted = 1, isLoop = :isLoop, summary = :summary, " +
            "sourceQuote = :sourceQuote, direction = :direction, dueAt = :dueAt, " +
            "dueAtExplicit = :dueAtExplicit, personName = :personName, " +
            "category = :category, projectName = :projectName WHERE id = :id"
    )
    suspend fun updateExtraction(
        id: Long,
        isLoop: Boolean?,
        summary: String?,
        sourceQuote: String?,
        direction: String?,
        dueAt: Long?,
        dueAtExplicit: Boolean,
        personName: String?,
        category: String?,
        projectName: String?
    )

    /** Marks a row processed without surfacing anything — the common case. */
    @Query("UPDATE memories SET extracted = 1, isLoop = 0 WHERE id = :id")
    suspend fun markNotAMemory(id: Long)

    // ── dedup ────────────────────────────────────────────────────────────

    /** Persisted rather than an in-memory cache: an in-process "recently
     *  seen" map resets on every process restart, which defeats the purpose
     *  for a screen that legitimately sits unchanged for hours. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM memories WHERE packageName = :packageName " +
            "AND rawText = :rawText AND timestamp > :since)"
    )
    suspend fun hasRecentExactText(packageName: String, rawText: String, since: Long): Boolean

    /** No default argument: Room generates the implementation and does not
     *  carry Kotlin defaults through reliably. Callers pass the bound. */
    @Query(
        "SELECT embedding FROM memories WHERE packageName = :packageName " +
            "AND timestamp > :since AND embedding IS NOT NULL LIMIT :limit"
    )
    suspend fun getRecentEmbeddings(packageName: String, since: Long, limit: Int): List<ByteArray>

    /** Recent raw text for the same app, for the token-overlap dedup gate. */
    @Query(
        "SELECT rawText FROM memories WHERE packageName = :packageName " +
            "AND timestamp > :since ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRecentTexts(packageName: String, since: Long, limit: Int): List<String>

    @Query("SELECT summary FROM memories WHERE isLoop = 1 AND summary IS NOT NULL")
    suspend fun getAllSummaries(): List<String>

    // ── Today ────────────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND dueAt >= :start AND dueAt < :end " +
            "ORDER BY dueAt ASC"
    )
    fun observeDueBetween(start: Long, end: Long): Flow<List<MemoryEntity>>

    /**
     * Memories from a day, by when they were captured.
     *
     * The timeline used observeDueBetween, which only ever showed memories
     * carrying a deadline. Measured on a real device: 17 memories, 2 with a
     * dueAt, 1 due today — so the day's timeline showed one row out of
     * seventeen, and everything the app understood about the day was invisible
     * unless the model had also found a date in it.
     *
     * A timeline of the day is what happened, not what is scheduled. Deadlines
     * still drive the commitments list and the reminders.
     */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND summary IS NOT NULL " +
            "AND timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC"
    )
    fun observeCapturedBetween(start: Long, end: Long): Flow<List<MemoryEntity>>

    @Query(
        "SELECT COUNT(*) FROM memories WHERE isLoop = 1 AND summary IS NOT NULL " +
            "AND timestamp >= :start AND timestamp < :end"
    )
    fun observeCountCapturedBetween(start: Long, end: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories WHERE isLoop = 1 AND dueAt >= :start AND dueAt < :end")
    fun observeCountDueBetween(start: Long, end: Long): Flow<Int>

    /** Open obligations only — the "2 open" in the section header and the
     *  denominator of the morning brief's ring. */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND commitmentDone = 0 " +
            "AND direction IN ('SELF_PROMISE', 'THEY_ASKED') ORDER BY dueAt ASC"
    )
    fun observeOpenCommitments(): Flow<List<MemoryEntity>>

    /** Everything understood, newest first — the Memories screen.
     *  Bounded because this is a scrolling list, not an export. */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND summary IS NOT NULL " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeAllMemories(limit: Int): Flow<List<MemoryEntity>>

    /** The same set as [observeOpenCommitments], fetched once for the
     *  extraction prompt. Bounded because every one of these is prefill paid
     *  on every captured screen in the pass. */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND commitmentDone = 0 " +
            "AND direction IN ('SELF_PROMISE', 'THEY_ASKED') " +
            "AND summary IS NOT NULL ORDER BY dueAt ASC LIMIT :limit"
    )
    suspend fun getOpenCommitments(limit: Int): List<MemoryEntity>

    @Query(
        "SELECT COUNT(*) FROM memories WHERE isLoop = 1 AND commitmentDone = 1 " +
            "AND dueAt >= :start AND dueAt < :end"
    )
    fun observeKeptBetween(start: Long, end: Long): Flow<Int>

    @Query("UPDATE memories SET commitmentDone = :done WHERE id = :id")
    suspend fun setCommitmentDone(id: Long, done: Boolean)

    // ── Calendar ─────────────────────────────────────────────────────────

    /** Whole-month fetch so the grid can be dotted in one query rather than
     *  31 separate day queries. */
    /**
     * A month's memories, placed on the day they belong to.
     *
     * Dated ones sit on their deadline; undated ones sit on the day they were
     * captured. The caller already assumed this — it groups by
     * `dueAt ?: timestamp` — but the query only returned rows with a dueAt, so
     * that fallback was unreachable and the calendar showed only the small
     * minority of memories carrying a date. Measured on a real database: 2 of
     * 31. The same fault the timeline had.
     */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND summary IS NOT NULL AND (" +
            "(dueAt IS NOT NULL AND dueAt >= :start AND dueAt < :end) OR " +
            "(dueAt IS NULL AND timestamp >= :start AND timestamp < :end)) " +
            "ORDER BY IFNULL(dueAt, timestamp) ASC"
    )
    fun observeMonth(start: Long, end: Long): Flow<List<MemoryEntity>>

    // ── People ───────────────────────────────────────────────────────────

    /** Newest-contact-first. MAX(timestamp) needs the GROUP BY here rather
     *  than an outer ORDER BY: SQLite only guarantees the aggregate value,
     *  not that the surrounding rows come back sorted by it. */
    @Query(
        "SELECT personName FROM memories WHERE isLoop = 1 AND personName IS NOT NULL " +
            "GROUP BY personName ORDER BY MAX(timestamp) DESC"
    )
    fun observeDistinctPeople(): Flow<List<String>>

    /** Apps that have actually produced captures, busiest first.
     *
     *  The allow-list is built from this rather than from PackageManager's
     *  full app list: querying every installed package needs QUERY_ALL_PACKAGES
     *  on Android 11+, which is a heavy permission to hold for a settings
     *  screen — and the apps worth deciding about are precisely the ones
     *  already generating rows.
     */
    @Query(
        "SELECT packageName AS packageName, COUNT(*) AS captureCount FROM memories " +
            "WHERE packageName != '' AND originDevice IS NULL " +
            "GROUP BY packageName ORDER BY captureCount DESC"
    )
    fun observePackageCounts(): Flow<List<PackageCount>>

    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND personName = :person " +
            "ORDER BY timestamp DESC"
    )
    fun observeByPerson(person: String): Flow<List<MemoryEntity>>

    /** One-shot version for the extraction prompt, which needs the list inside
     *  a worker rather than as an observable stream. */
    @Query(
        "SELECT personName FROM memories WHERE isLoop = 1 AND personName IS NOT NULL " +
            "GROUP BY personName ORDER BY MAX(timestamp) DESC LIMIT :limit"
    )
    suspend fun getRecentPeople(limit: Int): List<String>

    /** The three counters in the Person header, in one query rather than
     *  three round trips. */
    /** SUM returns NULL over zero rows, so the counts are wrapped in IFNULL —
     *  otherwise a person with no matching rows yields nulls into non-null
     *  Int fields and Room fails at runtime rather than returning zeroes.
     *  `openCount` rather than `open`, which reads badly as a Kotlin property. */
    @Query(
        "SELECT " +
            "IFNULL(SUM(CASE WHEN commitmentDone = 0 AND direction IN ('SELF_PROMISE','THEY_ASKED') THEN 1 ELSE 0 END), 0) AS openCount, " +
            // Gated on direction to match openCount. Without it "Kept" counted
            // any ticked row, including things that were never an obligation —
            // so the pair read as 1 open and 4 kept for a person who had only
            // ever been asked one thing.
            "IFNULL(SUM(CASE WHEN commitmentDone = 1 AND direction IN ('SELF_PROMISE','THEY_ASKED') THEN 1 ELSE 0 END), 0) AS kept, " +
            "COUNT(*) AS total, " +
            "MAX(timestamp) AS lastAt " +
            "FROM memories WHERE isLoop = 1 AND personName = :person"
    )
    fun observePersonStats(person: String): Flow<PersonStats>

    @Query("SELECT COUNT(*) FROM memories WHERE isLoop = 1 AND personName = :person")
    suspend fun countForPerson(person: String): Int

    // ── Projects (data kept, no UI ships) ────────────────────────────────

    @Query(
        "SELECT projectName FROM memories WHERE isLoop = 1 AND projectName IS NOT NULL " +
            "GROUP BY projectName ORDER BY MAX(timestamp) DESC"
    )
    fun observeDistinctProjects(): Flow<List<String>>

    // ── Ask / retrieval ──────────────────────────────────────────────────
    /**
     * Candidate pool for RAG. Bounded and newest-first rather than unbounded:
     * similarity is scored in Kotlin over these rows, so an unbounded fetch
     * would deserialise every vector ever stored on each question asked.
     *
     * Deliberately does NOT require an embedding. It used to, which made Ask
     * permanently unable to answer: with no embedder installed nothing was
     * ever embedded, so this returned zero rows for every question, and the
     * keyword fallback — the entire point of which is to work without
     * embeddings — was never reached, because the query feeding it filtered
     * on the thing it exists to do without. Measured on a real device: 16
     * memories with summaries, 0 with embeddings, every question unanswerable.
     *
     * Rows without a vector are skipped by the vector path itself, which
     * null-checks each one, so nothing downstream needs this filter.
     */
    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND summary IS NOT NULL " +
            "ORDER BY timestamp DESC LIMIT :limit"
    )
    suspend fun getRetrievalCandidates(limit: Int): List<MemoryEntity>

    // ── Brief / digest ───────────────────────────────────────────────────

    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND summary IS NOT NULL " +
            "AND timestamp >= :start AND timestamp < :end ORDER BY timestamp ASC"
    )
    suspend fun getCapturedBetween(start: Long, end: Long): List<MemoryEntity>

    // ── Reminders ────────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM memories WHERE isLoop = 1 AND dueAt IS NOT NULL " +
            "AND reminderSent = 0 ORDER BY dueAt ASC"
    )
    suspend fun getPendingReminders(): List<MemoryEntity>

    @Query("UPDATE memories SET reminderSent = 1 WHERE id = :id")
    suspend fun markReminderSent(id: Long)

    // ── Cloud sync ───────────────────────────────────────────────────────
    // Copied from Wick unchanged. syncedToCloud was already carried on the
    // entity from the start precisely so this could be added without a
    // migration.

    @Query("SELECT * FROM memories WHERE isLoop = 1 AND syncedToCloud = 0")
    suspend fun getPendingCloudSync(): List<MemoryEntity>

    @Query("UPDATE memories SET syncedToCloud = 1 WHERE id = :id")
    suspend fun markSyncedToCloud(id: Long)

    // ── Profile / capture stats ──────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM memories")
    fun observeTotalCaptured(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memories WHERE isLoop = 1")
    fun observeMemoryCount(): Flow<Int>

    /** Feeds the hourly histogram on the You screen. */
    @Query("SELECT timestamp FROM memories WHERE timestamp >= :since")
    fun observeTimestampsSince(since: Long): Flow<List<Long>>

    @Query("SELECT SUM(LENGTH(rawText)) FROM memories")
    fun observeApproximateBytes(): Flow<Long?>

    // ── Erase ────────────────────────────────────────────────────────────

    @Query("DELETE FROM memories")
    suspend fun clearAll()

    /**
     * One row, by id.
     *
     * "Erase all" was the only way out until now, which made a single wrong
     * reading — a booking confirmation read as a commitment, a group chat read
     * as a promise — cost the user their whole history to correct.
     */
    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** Projection for [MemoryDao.observePersonStats]. */
data class PersonStats(
    val openCount: Int,
    val kept: Int,
    val total: Int,
    val lastAt: Long?
)
