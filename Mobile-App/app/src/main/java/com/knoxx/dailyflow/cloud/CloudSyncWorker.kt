package com.knoxx.dailyflow.cloud

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.knoxx.dailyflow.cloud.AuthRepository
import com.knoxx.dailyflow.cloud.CloudSyncConsent
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.cloud.SupabaseClientProvider
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Periodic, requires network — pushes surfaced memories (isLoop=1) up to
 *  Supabase so Claude can query them via MCP. Raw captures never leave this
 *  device; only the already-extracted, already-understood result does. */
class CloudSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "CloudSyncWorker"
        private val mutex = Mutex()
    }

    override suspend fun doWork(): Result = mutex.withLock {
        val userId = AuthRepository.currentUserId
            ?: return@withLock Result.success() // not signed in — nothing to sync to, leave pending

        if (!CloudSyncConsent.hasConsented(applicationContext)) {
            return@withLock Result.success() // owner hasn't opted in yet — leave everything pending
        }

        val pending = MemoryRepository.getPendingCloudSync()
        if (pending.isEmpty()) return@withLock Result.success()

        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        for (memory in pending) {
            try {
                val summary = memory.summary ?: continue
                // insert_memory() encrypts summary server-side before storing it
                // (see supabase/migrations/20260816000004_encrypt_memories_summary.sql)
                // — the plaintext never lands in a plain column, only ever passed
                // as this one RPC argument over TLS.
                val params = buildJsonObject {
                    put("p_user_id", userId)
                    put("p_package_name", memory.packageName)
                    put("p_summary", summary)
                    put("p_embedding", memory.embedding?.let { vec -> "[" + vec.joinToString(",") + "]" })
                    put("p_captured_at", isoFormat.format(Date(memory.timestamp)))
                }
                SupabaseClientProvider.client.postgrest.rpc("insert_memory", params)
                MemoryRepository.markSyncedToCloud(memory.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync memory ${memory.id}, will retry next run", e)
                // leave this one unsynced, continue with the rest of the batch
            }
        }
        Result.success()
    }
}
