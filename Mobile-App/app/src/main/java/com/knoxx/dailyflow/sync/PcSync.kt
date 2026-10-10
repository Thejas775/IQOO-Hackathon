package com.knoxx.dailyflow.sync

import android.content.Context
import androidx.room.withTransaction
import com.knoxx.dailyflow.data.DailyFlowDatabase
import com.knoxx.dailyflow.data.MemoryEntity
import com.knoxx.dailyflow.data.SyncTombstone
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.work.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Keeps this phone's memories and a paired PC's in step, over the local
 * Wi-Fi. The phone always starts the exchange; the PC only listens.
 *
 * What travels is what DailyFlow understood — summary, quote, person,
 * direction, deadline, done, deletions — and never the raw screen text behind
 * it. Each memory keeps the device it was made on, so a card can say so.
 *
 * Conflicts: the newer change wins (updatedAt), and a deletion beats any
 * edit made before it. Both devices keep wall-clock time from the network,
 * which is all last-writer-wins needs for a single owner's two devices.
 */
object PcSync {

    private const val BATCH = 200
    private const val MAX_ROUNDS = 50

    private val mutex = Mutex()

    data class Outcome(val ok: Boolean, val message: String)

    // ── pairing ─────────────────────────────────────────────────────────

    suspend fun pair(context: Context, host: String, port: Int, code: String): Outcome =
        withContext(Dispatchers.IO) {
            val phoneId = PairedComputers.deviceId(context)
            try {
                val start = PcPairing.start(code)
                val r1 = SyncProtocol.request(
                    host, port,
                    JSONObject()
                        .put("type", "pair1")
                        .put("deviceId", phoneId)
                        .put("name", PairedComputers.deviceName())
                        .put("X", start.elementHex)
                )
                if (r1.optString("type") != "pair2") {
                    return@withContext Outcome(false, r1.optString("message", "The PC refused."))
                }
                val pcId = r1.getString("pcId")
                val keys = PcPairing.finish(start, phoneId, pcId, r1.getString("Y"))
                    ?: return@withContext Outcome(false, "The PC sent an invalid reply.")
                // Checked before revealing ours: a wrong code stops here, and
                // the PC learns nothing it could test other codes against.
                if (!MessageDigest.isEqual(keys.confirmPc, r1.getString("confirm").hexToBytes())) {
                    return@withContext Outcome(false, "Wrong code. Check the code on the PC and try again.")
                }
                val r2 = SyncProtocol.request(
                    host, port,
                    JSONObject()
                        .put("type", "pair3")
                        .put("deviceId", phoneId)
                        .put("confirm", keys.confirmPhone.toHex())
                )
                if (r2.optString("type") != "paired") {
                    return@withContext Outcome(false, r2.optString("message", "Pairing failed."))
                }
                val name = r1.optString("pcName", "Computer")
                PairedComputers.save(
                    context,
                    PairedComputer(id = pcId, name = name, key = keys.syncKey, host = host, port = port)
                )
                DebugLog.log("pcsync", "paired with $name at $host")
                Outcome(true, "Paired with $name")
            } catch (e: Exception) {
                DebugLog.log("pcsync", "pairing failed: ${e.message}")
                Outcome(false, "Couldn't reach the PC (${e.javaClass.simpleName}). " +
                    "Check both are on the same Wi-Fi and DailyFlow is open on the PC.")
            }
        }

    // ── sync ────────────────────────────────────────────────────────────

    suspend fun syncAll(context: Context): List<Outcome> =
        PairedComputers.all(context).map { syncOne(context, it.id) }

    suspend fun syncOne(context: Context, computerId: String): Outcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pc = PairedComputers.all(context).firstOrNull { it.id == computerId }
                ?: return@withContext Outcome(false, "Not paired")
            try {
                val outcome = exchange(context, pc)
                val latest = PairedComputers.all(context).firstOrNull { it.id == pc.id }
                if (latest != null) {
                    PairedComputers.save(context, latest.copy(lastError = if (outcome.ok) null else outcome.message))
                }
                outcome
            } catch (e: Exception) {
                val message = "Couldn't reach ${pc.name} — is it on, with DailyFlow open, on this Wi-Fi?"
                DebugLog.log("pcsync", "sync with ${pc.name} failed: ${e.javaClass.simpleName}: ${e.message}")
                PairedComputers.save(context, pc.copy(lastError = message))
                Outcome(false, message)
            }
        }
    }

    private suspend fun exchange(context: Context, start: PairedComputer): Outcome {
        val db = DailyFlowDatabase.getInstance(context)
        val sync = db.syncDao()
        val phoneId = PairedComputers.deviceId(context)
        val phoneName = PairedComputers.deviceName()
        var pc = start
        var host = pc.host ?: locate(context, pc) ?: return Outcome(false, "${pc.name} isn't on this Wi-Fi.")
        var sent = 0
        var received = 0

        repeat(MAX_ROUNDS) {
            // What changed here since the PC last acknowledged, rows and
            // deletions merged in change order so one number covers both.
            val rows = sync.changesSince(pc.pushedSeq, pc.id, BATCH + 1).map { it.changeSeq to it }
            val tombs = sync.tombstonesSince(pc.pushedSeq, pc.id, BATCH + 1).map { it.changeSeq to it }
            val merged = (rows + tombs).sortedBy { it.first }
            val localMore = merged.size > BATCH
            val batch = merged.take(BATCH)

            val body = JSONObject()
                .put("name", phoneName)
                .put("cursor", pc.pcCursor)
                .put("rows", JSONArray().apply {
                    batch.forEach { (_, x) -> if (x is MemoryEntity) put(SyncProtocol.rowToJson(x, phoneId, phoneName)) }
                })
                .put("tombstones", JSONArray().apply {
                    batch.forEach { (_, x) ->
                        if (x is SyncTombstone) put(SyncProtocol.tombstoneToJson(x))
                    }
                })
            val request = SyncProtocol.seal(pc.key, phoneId, body)
                .put("type", "sync")
                .put("deviceId", phoneId)

            val reply = try {
                SyncProtocol.request(host, pc.port, request)
            } catch (e: Exception) {
                // The PC's address can change (DHCP); look for it again once.
                val found = locate(context, pc) ?: throw e
                host = found
                SyncProtocol.request(host, pc.port, request)
            }
            if (reply.optString("type") != "sync") {
                if (reply.optString("code") == "unpaired") {
                    return Outcome(false, "${pc.name} no longer knows this phone. Remove it and pair again.")
                }
                return Outcome(false, reply.optString("message", "The PC refused the sync."))
            }
            val answer = SyncProtocol.open(pc.key, phoneId, reply)
            received += applyAnswer(context, db, pc, answer)
            sent += batch.size

            pc = pc.copy(
                host = host,
                pcCursor = answer.getLong("cursor"),
                pushedSeq = if (batch.isNotEmpty()) batch.last().first else pc.pushedSeq,
                lastSyncAt = System.currentTimeMillis(),
                name = answer.optString("pcName", pc.name)
            )
            PairedComputers.save(context, pc)
            if (!answer.optBoolean("more") && !localMore) {
                DebugLog.log("pcsync", "${pc.name}: sent $sent, received $received")
                return Outcome(true, "Synced with ${pc.name}")
            }
        }
        return Outcome(true, "Synced with ${pc.name} (more next time)")
    }

    private suspend fun locate(context: Context, pc: PairedComputer): String? =
        LanDiscovery.find(context).firstOrNull { it.id == pc.id }?.host

    /** Applies the PC's changes. Returns how many rows changed here. */
    private suspend fun applyAnswer(
        context: Context,
        db: DailyFlowDatabase,
        pc: PairedComputer,
        answer: JSONObject
    ): Int {
        val sync = db.syncDao()
        val memories = db.memoryDao()
        val myId = PairedComputers.deviceId(context)
        val now = System.currentTimeMillis()
        var changed = 0
        val toSchedule = mutableListOf<Pair<Long, Long>>()
        val toCancel = mutableListOf<Long>()

        db.withTransaction {
            val rows = answer.optJSONArray("rows") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val w = rows.getJSONObject(i)
                val syncId = w.getString("syncId")
                val updatedAt = w.getLong("updatedAt")
                val tomb = sync.tombstone(syncId)
                if (tomb != null && tomb.deletedAt >= updatedAt) continue

                val dueAt = w.longOrNull("dueAt")
                val done = w.optBoolean("commitmentDone")
                val existing = sync.bySyncId(syncId)
                if (existing == null) {
                    val origin = w.optJSONObject("origin") ?: JSONObject()
                    val originId = origin.stringOrNull("id")
                    // A memory this phone made, coming back after an erase,
                    // is this phone's again rather than "from" itself.
                    val mine = originId == myId
                    val id = memories.insert(
                        MemoryEntity(
                            timestamp = w.getLong("timestamp"),
                            packageName = w.stringOrNull("source") ?: "",
                            rawText = "",
                            eventType = w.stringOrNull("eventType"),
                            extracted = true,
                            isLoop = true,
                            summary = w.stringOrNull("summary"),
                            sourceQuote = w.stringOrNull("sourceQuote"),
                            direction = w.stringOrNull("direction"),
                            dueAt = dueAt,
                            dueAtExplicit = w.optBoolean("dueAtExplicit"),
                            // The PC already nudged about anything due before
                            // it arrived; a first sync must not fire a burst
                            // of stale reminders here.
                            reminderSent = (dueAt != null && dueAt <= now) || done,
                            personName = w.stringOrNull("personName"),
                            category = w.stringOrNull("category"),
                            projectName = w.stringOrNull("projectName"),
                            commitmentDone = done,
                            syncId = syncId,
                            originDevice = if (mine) null else originId,
                            originName = if (mine) null else origin.stringOrNull("name"),
                            originKind = if (mine) null else origin.stringOrNull("kind"),
                            updatedAt = updatedAt
                        )
                    )
                    sync.markFrom(id, pc.id)
                    if (dueAt != null && dueAt > now && !done) toSchedule += id to dueAt
                    changed++
                } else if (updatedAt > existing.updatedAt) {
                    sync.applyUpdate(
                        id = existing.id,
                        summary = w.stringOrNull("summary"),
                        sourceQuote = w.stringOrNull("sourceQuote"),
                        direction = w.stringOrNull("direction"),
                        dueAt = dueAt,
                        dueAtExplicit = w.optBoolean("dueAtExplicit"),
                        personName = w.stringOrNull("personName"),
                        category = w.stringOrNull("category"),
                        projectName = w.stringOrNull("projectName"),
                        done = done,
                        updatedAt = updatedAt,
                        peer = pc.id
                    )
                    // Ticked off on the PC: the phone's nudge must not ring.
                    if (done) toCancel += existing.id
                    else if (dueAt != null && dueAt > now && !existing.reminderSent) toSchedule += existing.id to dueAt
                    changed++
                }
            }

            val tombs = answer.optJSONArray("tombstones") ?: JSONArray()
            for (i in 0 until tombs.length()) {
                val t = tombs.getJSONObject(i)
                val syncId = t.getString("syncId")
                val deletedAt = t.getLong("deletedAt")
                val existing = sync.bySyncId(syncId)
                if (existing != null && existing.updatedAt > deletedAt) continue
                sync.addTombstone(syncId, deletedAt, pc.id)
                if (existing != null) {
                    sync.deleteRow(existing.id)
                    toCancel += existing.id
                    changed++
                }
            }
        }

        // Outside the transaction: WorkManager writes to its own database.
        toSchedule.forEach { (id, due) -> ReminderScheduler.schedule(context, id, due) }
        toCancel.forEach { ReminderScheduler.cancel(context, it) }
        return changed
    }
}
