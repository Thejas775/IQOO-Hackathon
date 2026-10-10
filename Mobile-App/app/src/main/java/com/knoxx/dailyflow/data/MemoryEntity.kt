package com.knoxx.dailyflow.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One captured moment's journey through the pipeline: raw text in at capture,
 * [embedding] filled in on-device, then [summary] and the rest filled in by
 * Gemma. Everything the app knows lives in this one table.
 *
 * The flags are deliberately separate rather than inferred from each other.
 * [extracted] is not the same as `summary != null`: a row correctly judged
 * *not* worth remembering legitimately has a null summary, so collapsing the
 * two would make "done, nothing here" indistinguishable from "not processed
 * yet" and retry that row forever. Wick learned this the hard way.
 */
@Suppress("ArrayInDataClass") // FloatArray equals/hashCode is identity-based;
// rows are only ever compared by id, never structurally.
@Entity(
    tableName = "memories",
    indices = [
        // Dedup checks every capture against recent rows for the same app, and
        // the Today/Calendar screens query by due date. Both are hot paths on
        // a table that grows all day.
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["dueAt"]),
        Index(value = ["personName"]),
        Index(value = ["extracted"]),
        // Every synced row is looked up by its cross-device id on each sync.
        Index(value = ["syncId"], unique = true)
    ]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** When the screen was captured, not when it was understood. */
    val timestamp: Long,

    /** Source app. Drives the badge on every card; see ui/SourceApp.kt. */
    val packageName: String,

    /** Flattened on-screen text. Never leaves the device. */
    val rawText: String,

    /**
     * Which capture route produced this row: "NOTIFICATION" from the listener,
     * an accessibility event type from the screen reader, "MANUAL" when typed.
     *
     * Was previously carried on Capture for debounce only and then dropped on
     * insert, which made notification-sourced rows indistinguishable from
     * screen scrapes — so there was no way to tell which half of capture was
     * misbehaving. Stored now precisely so that is answerable.
     */
    val eventType: String? = null,

    // ── on-device understanding ──────────────────────────────────────────
    val embedding: FloatArray? = null,

    /**
     * Which model produced [embedding]. Vectors from different models are not
     * comparable, and cosine similarity returns 0 on a length mismatch rather
     * than raising — so without this, swapping embedders would silently
     * degrade dedup and search to "nothing ever matches" with no error.
     */
    val embeddingModel: String? = null,

    /** Set once Gemma has processed this row, whatever the verdict. */
    val extracted: Boolean = false,

    /** One-sentence summary in second person. Null when not worth keeping. */
    val summary: String? = null,

    /** Whether this surfaced as a memory. Derived from whether a summary was
     *  actually written, not from the model's own boolean — measured, small
     *  models contradict their own flag. */
    val isLoop: Boolean? = null,

    /**
     * The verbatim line the commitment came from, shown italic under the
     * summary. Only stored when it was verified to appear in [rawText] — a
     * paraphrase presented as a quote is worse than no quote at all on a
     * product whose whole claim is trustworthy memory.
     */
    val sourceQuote: String? = null,

    /** [Direction] name. Drives the pill copy and colour on every card. */
    val direction: String? = null,

    // ── scheduling ───────────────────────────────────────────────────────
    val dueAt: Long? = null,

    /** True when [dueAt] came from a real deadline in the text, false when it
     *  is the generic next-morning fallback. Purely informational, but it lets
     *  the UI avoid implying a precision the source never had. */
    val dueAtExplicit: Boolean = false,

    val reminderSent: Boolean = false,

    // ── grouping ─────────────────────────────────────────────────────────
    val personName: String? = null,
    val category: String? = null,

    /** Still extracted and stored even though no Projects screen ships — the
     *  data costs nothing to keep and re-extracting history later would be
     *  impossible once the raw capture has rotated away. */
    val projectName: String? = null,

    val commitmentDone: Boolean = false,

    /**
     * Reserved for the deferred MCP sync. Carried in v1 on purpose: adding a
     * column later means a migration, and this one is a single unused byte
     * until that feature lands.
     */
    val syncedToCloud: Boolean = false,

    // ── PC sync (sync/PcSync.kt) ─────────────────────────────────────────

    /**
     * The row's id on every device. Local ids are autoincrement and collide
     * across a phone and a PC, so a memory needs one that both agree on.
     * Assigned when the row first becomes a memory; null for captures that
     * never did, which therefore never leave this phone.
     */
    val syncId: String? = null,

    /** Where it was made. Null means this phone; otherwise the paired PC's
     *  id, name and kind ("pc"), so a card can say which device saw it. */
    val originDevice: String? = null,
    val originName: String? = null,
    val originKind: String? = null,

    /** When it last changed on any device. The newer change wins a conflict. */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,

    /** This phone's change counter: a sync sends rows changed since the last
     *  number the PC acknowledged, rather than everything every time. */
    @ColumnInfo(defaultValue = "0") val changeSeq: Long = 0,

    /** The PC a change came from, so it is not sent straight back to it. */
    val lastChangedBy: String? = null
) {
    /** True when this memory was made on a paired computer and synced here. */
    val fromComputer: Boolean get() = originKind == "pc"

    /** Typed accessor for the string column. */
    val directionOrNull: Direction?
        get() = direction?.let { runCatching { Direction.valueOf(it) }.getOrNull() }
}
