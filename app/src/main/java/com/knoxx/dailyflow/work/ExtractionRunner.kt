package com.knoxx.dailyflow.work

import android.content.Context
import android.util.Log
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog
import com.knoxx.dailyflow.llm.ExtractionParser
import com.knoxx.dailyflow.llm.ExtractionPrompt
import com.knoxx.dailyflow.llm.GemmaEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive

/**
 * The extraction pass itself, shared by the scheduled run and the manual one.
 *
 * Deliberately not a worker and not a service — it is just the loop, so both
 * callers execute identical logic and the UI observes one progress source
 * regardless of which triggered it.
 *
 * The model is loaded here and released at the end of every pass. That
 * load/work/unload cycle is the whole reason this is affordable: steady-state
 * memory between runs is zero, so the process spends 15 minutes cheap and
 * ~30 seconds expensive rather than holding several gigabytes all day.
 */
object ExtractionRunner {

    private const val TAG = "ExtractionRunner"

    /** Screens per inference.
     *
     *  One per call was the pipeline's binding constraint at roughly ten
     *  seconds each. Five share one context window comfortably: real captures
     *  measured 770 to 3,120 characters, and the batch prompt truncates each to
     *  2,200, so five worst-case screens are about 2,750 tokens of input inside
     *  an 8,192 window. Larger batches would fit but raise the cost of a single
     *  unparseable reply, since a whole batch is retried together. */
    private const val BATCH_SIZE = 5

    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val message: String? = null
    ) {
        val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    /**
     * Drains the pending queue. Suspends until finished or cancelled.
     *
     * [onProgress] lets the caller update its foreground notification; the
     * shared [progress] flow is updated regardless so any open UI follows the
     * same run.
     */
    suspend fun runOnce(
        context: Context,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ) {
        val app = context.applicationContext
        val pending = MemoryRepository.pendingExtraction()

        if (pending.isEmpty()) {
            _progress.value = Progress(message = "Nothing new to read")
            return
        }
        if (!GemmaEngine.isModelAvailable(app)) {
            _progress.value = Progress(message = "Model not installed")
            return
        }

        DebugLog.log("extract", "pass start: ${pending.size} pending")
        _progress.value = Progress(running = true, total = pending.size)
        onProgress(0, pending.size)

        // Claims the engine for this pass, so the activity going to the
        // background cannot unload the model mid-run and force a reload on the
        // very next row.
        GemmaEngine.pin()

        var done = 0
        try {
            val people = MemoryRepository.recentPeople()
            // Fetched once and then maintained in place. Re-querying per row
            // would be a database round trip per inference, and a row ticked
            // off earlier in this same pass must not be offered again.
            val open = MemoryRepository.openCommitments().mapNotNull { memory ->
                memory.summary?.let { memory.id to it }
            }.toMutableList()

            for (batch in pending.chunked(BATCH_SIZE)) {
                if (!currentCoroutineContext().isActive) break

                val reply = GemmaEngine.complete(
                    app,
                    ExtractionPrompt.buildJsonBatch(
                        batch.map { Triple(it.packageName, it.timestamp, it.rawText) },
                        people,
                        open
                    )
                )

                if (reply == null) {
                    // Engine failure, not a judgement about these rows. Stop
                    // rather than grind through the rest failing identically;
                    // the remaining rows stay pending for the next pass.
                    Log.w(TAG, "Inference returned nothing; ${pending.size - done} left pending")
                    _progress.value = Progress(done = done, total = pending.size, message = "Couldn't finish")
                    return
                }

                val results = ExtractionParser.parseJsonBatch(reply, batch.size)
                if (results.isEmpty()) {
                    // The whole batch is unreadable. Leaving these rows pending
                    // is the only safe move — marking them "not a memory" would
                    // discard real content because of a formatting failure.
                    DebugLog.log("extract", "batch of ${batch.size} unparseable, left pending")
                    Log.w(TAG, "Unparseable batch reply; ${batch.size} rows left pending")
                    continue
                }

                batch.forEachIndexed { indexInBatch, row ->
                    val parsed = results[indexInBatch + 1]
                    if (parsed == null) {
                        // The model skipped this screen. Left pending rather
                        // than guessed at — matching by position instead would
                        // file one screen's summary against another's row.
                        DebugLog.log("extract", "row ${row.id} missing from batch reply")
                        return@forEachIndexed
                    }

                    // The quote is checked here rather than in the parser,
                    // which has no way to know which screen each object came
                    // from. Unverified, a fabricated quote would be shown to
                    // the user as their own words.
                    val result = ExtractionParser.verifyQuoteAgainst(parsed, row.rawText)

                    DebugLog.log(
                        "extract",
                        "row ${row.id} ${row.packageName} memory=${result.isMemory} " +
                            "due=${result.dueAt} explicit=${result.dueAtExplicit} " +
                            "person=${result.person} :: ${row.rawText.take(50).replace('\n', ' ')}"
                    )

                    // Auto-tick. Validated against the list actually sent
                    // rather than trusted: an out-of-range number is the
                    // expected failure mode for a small model asked for an
                    // ordinal, and acting on it would mark an unrelated
                    // commitment done. Out of range is dropped, not clamped.
                    result.completesIndex?.let { index ->
                        val target = open.getOrNull(index - 1)
                        if (target == null) {
                            DebugLog.log("autotick", "ignored out-of-range $index (open=${open.size})")
                        } else {
                            MemoryRepository.setDone(target.first, true)
                            ReminderScheduler.cancel(app, target.first)
                            open.removeAt(index - 1)
                            DebugLog.log("autotick", "done id=${target.first} :: ${target.second}")
                        }
                    }

                    if (result.isMemory) {
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
                        // Scheduled only for rows that carry a deadline. A
                        // memory with no due time is still worth keeping, but
                        // there is no moment at which nudging about it is right.
                        result.dueAt?.let { due ->
                            ReminderScheduler.schedule(app, row.id, due)
                        }
                    } else {
                        MemoryRepository.markNotAMemory(row.id)
                    }

                    done++
                }

                _progress.value = Progress(running = true, done = done, total = pending.size)
                onProgress(done, pending.size)
            }
            _progress.value = Progress(
                done = done,
                total = pending.size,
                message = if (done == 0) "Nothing new" else "Read $done"
            )
        } catch (e: CancellationException) {
            _progress.value = Progress(done = done, total = pending.size, message = "Stopped")
            throw e
        } finally {
            // Always released, including on cancellation. Leaving several
            // gigabytes resident after the pass is what makes the process the
            // most attractive thing on the device to kill.
            DebugLog.log("extract", "pass end: read $done of ${pending.size}")
            GemmaEngine.unpin()
            GemmaEngine.unload()
        }
    }

    /** Clears a finished run's message so the button returns to rest. */
    fun clearMessage() {
        if (!_progress.value.running) _progress.value = Progress()
    }
}
