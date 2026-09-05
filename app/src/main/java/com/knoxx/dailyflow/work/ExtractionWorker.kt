package com.knoxx.dailyflow.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.llm.ExtractionParser
import com.knoxx.dailyflow.llm.ExtractionPrompt
import com.knoxx.dailyflow.llm.GemmaEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns captured screens into memories, one inference at a time.
 *
 * One row per inference, not Wick's batches of twenty. Batching existed to
 * amortise a network round trip; on-device there is no round trip to amortise,
 * and a batch would have to fit twenty screens plus their instructions into
 * one context window and then survive the model getting any single one of the
 * twenty wrong.
 *
 * The queue is drained in one run rather than one row per run: loading the
 * model costs far more than any single inference, so waking up repeatedly to
 * do one row each time would spend most of its time on setup.
 */
class ExtractionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = mutex.withLock {
        // Nothing to do — and importantly, don't load a 3.5 GB model to
        // discover that.
        if (!GemmaEngine.isModelAvailable(applicationContext)) {
            Log.d(TAG, "No model present; leaving ${MemoryRepository.pendingExtraction().size} rows pending")
            return@withLock Result.success()
        }

        var processed = 0
        while (true) {
            if (isStopped) break

            val pending = MemoryRepository.pendingExtraction()
            if (pending.isEmpty()) break

            // Fetched once per drain rather than per row: it changes only as
            // rows are written, and it is context for the model, not a lookup.
            val people = MemoryRepository.recentPeople()

            for (row in pending) {
                if (isStopped) break

                val prompt = ExtractionPrompt.build(
                    packageName = row.packageName,
                    capturedAt = row.timestamp,
                    rawText = row.rawText,
                    knownPeople = people
                )

                val reply = GemmaEngine.complete(applicationContext, prompt)
                if (reply == null) {
                    // Inference failed rather than judged this uninteresting.
                    // Leave the row pending so a later run retries it, and stop
                    // — if the engine is unhealthy the next row will fail too.
                    Log.w(TAG, "Inference returned nothing; leaving row ${row.id} pending")
                    return@withLock Result.retry()
                }

                val result = ExtractionParser.parse(reply, row.rawText)
                if (!result.isMemory) {
                    MemoryRepository.markNotAMemory(row.id)
                } else {
                    MemoryRepository.saveExtraction(
                        id = row.id,
                        summary = result.summary,
                        sourceQuote = result.sourceQuote,
                        direction = result.direction,
                        dueAt = result.dueAt,
                        dueAtExplicit = result.dueAtExplicit,
                        personName = result.person,
                        category = result.category,
                        projectName = result.project
                    )
                }
                processed++
            }
        }

        Log.d(TAG, "Extracted $processed row(s)")
        Result.success()
    }

    companion object {
        private const val TAG = "ExtractionWorker"

        /**
         * The scheduler kicks a run on every new capture and also keeps a
         * periodic safety net, so two runs can overlap. Without this lock both
         * would fetch the same still-pending rows and run the same inference
         * twice — wasteful anywhere, but here it also means competing for the
         * single engine lock.
         */
        private val mutex = Mutex()
    }
}
