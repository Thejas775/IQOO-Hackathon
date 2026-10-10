package com.knoxx.dailyflow.data

import androidx.room.Dao
import androidx.room.Query

/**
 * The next change number, shared by memories and tombstones so a single
 * cursor covers both. Computed in SQL inside each write rather than read and
 * then written, so two writes cannot be handed the same number.
 */
internal const val NEXT_SEQ =
    "(SELECT MAX(s) FROM (SELECT IFNULL(MAX(changeSeq), 0) AS s FROM memories " +
        "UNION ALL SELECT IFNULL(MAX(changeSeq), 0) AS s FROM sync_tombstones)) + 1"

/** Reads and writes for PC sync (sync/PcSync.kt). Same queries as the PC's
 *  dailyflow/store.py, so the two ends agree on what "changed" means. */
@Dao
interface SyncDao {

    /**
     * Marks a memory changed on this phone. Only understood memories travel:
     * a capture still waiting for Gemma, or judged not worth keeping, never
     * leaves the phone.
     */
    @Query(
        "UPDATE memories SET syncId = IFNULL(syncId, lower(hex(randomblob(16)))), " +
            "updatedAt = :now, changeSeq = $NEXT_SEQ, lastChangedBy = NULL " +
            "WHERE id = :id AND isLoop = 1 AND summary IS NOT NULL"
    )
    suspend fun touch(id: Long, now: Long)

    /** Changes after [seq], oldest first, except ones that came from [peer]. */
    @Query(
        "SELECT * FROM memories WHERE changeSeq > :seq AND syncId IS NOT NULL " +
            "AND isLoop = 1 AND summary IS NOT NULL AND IFNULL(lastChangedBy, '') != :peer " +
            "ORDER BY changeSeq ASC LIMIT :limit"
    )
    suspend fun changesSince(seq: Long, peer: String, limit: Int): List<MemoryEntity>

    @Query(
        "SELECT * FROM sync_tombstones WHERE changeSeq > :seq " +
            "AND IFNULL(lastChangedBy, '') != :peer ORDER BY changeSeq ASC LIMIT :limit"
    )
    suspend fun tombstonesSince(seq: Long, peer: String, limit: Int): List<SyncTombstone>

    @Query("SELECT syncId FROM memories WHERE id = :id")
    suspend fun syncIdOf(id: Long): String?

    @Query("SELECT * FROM memories WHERE syncId = :syncId")
    suspend fun bySyncId(syncId: String): MemoryEntity?

    @Query("SELECT * FROM sync_tombstones WHERE syncId = :syncId")
    suspend fun tombstone(syncId: String): SyncTombstone?

    @Query(
        "INSERT OR REPLACE INTO sync_tombstones(syncId, deletedAt, changeSeq, lastChangedBy) " +
            "VALUES(:syncId, :deletedAt, $NEXT_SEQ, :peer)"
    )
    suspend fun addTombstone(syncId: String, deletedAt: Long, peer: String?)

    /** After inserting a row that came from [peer]: number it, and mark it as
     *  theirs so it is not sent back. */
    @Query("UPDATE memories SET changeSeq = $NEXT_SEQ, lastChangedBy = :peer WHERE id = :id")
    suspend fun markFrom(id: Long, peer: String)

    @Query(
        "UPDATE memories SET summary = :summary, sourceQuote = :sourceQuote, " +
            "direction = :direction, dueAt = :dueAt, dueAtExplicit = :dueAtExplicit, " +
            "personName = :personName, category = :category, projectName = :projectName, " +
            "commitmentDone = :done, updatedAt = :updatedAt, " +
            "changeSeq = $NEXT_SEQ, lastChangedBy = :peer WHERE id = :id"
    )
    suspend fun applyUpdate(
        id: Long,
        summary: String?,
        sourceQuote: String?,
        direction: String?,
        dueAt: Long?,
        dueAtExplicit: Boolean,
        personName: String?,
        category: String?,
        projectName: String?,
        done: Boolean,
        updatedAt: Long,
        peer: String
    )

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteRow(id: Long)
}
