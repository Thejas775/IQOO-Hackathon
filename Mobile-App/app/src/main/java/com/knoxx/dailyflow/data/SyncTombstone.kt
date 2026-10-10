package com.knoxx.dailyflow.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A synced memory that was deleted, kept so the deletion reaches the paired
 * PC. Without it, a row removed here simply stops being sent — and the PC,
 * which still has it, sends it straight back on the next sync.
 */
@Entity(tableName = "sync_tombstones")
data class SyncTombstone(
    @PrimaryKey val syncId: String,
    val deletedAt: Long,
    val changeSeq: Long,
    /** The PC the deletion came from, if it came from one. */
    val lastChangedBy: String? = null
)
