package com.knoxx.dailyflow.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.knoxx.dailyflow.data.MemoryRepository
import com.knoxx.dailyflow.debug.DebugLog

/**
 * Fires one reminder, then records that it fired.
 *
 * Every reason to stay silent returns success rather than failure: the row was
 * deleted, already reminded, already done, or turned out not to be a
 * commitment. None of those are errors, and returning failure would make
 * WorkManager retry a notification the user has already dealt with.
 */
class ReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_MEMORY_ID, -1L)
        if (id == -1L) return Result.failure()

        val memory = MemoryRepository.byId(id) ?: run {
            DebugLog.log("remind", "skip id=$id (deleted)")
            return Result.success()
        }

        // Pulled out so the null check below actually narrows the type: a
        // smart cast does not survive being routed through `reason`.
        val summary = memory.summary?.takeIf { it.isNotBlank() }

        // Checked here rather than only at schedule time, because the gap
        // between the two is the whole point — the user may well have done the
        // thing while waiting, and reminding them then is worse than useless.
        val reason = when {
            memory.reminderSent -> "already sent"
            memory.commitmentDone -> "already done"
            memory.isLoop != true -> "not a commitment"
            summary == null -> "no summary"
            else -> null
        }
        if (reason != null || summary == null) {
            DebugLog.log("remind", "skip id=$id ($reason)")
            return Result.success()
        }

        ReminderScheduler.show(
            applicationContext,
            summary,
            memory.packageName,
            notificationId = id.toInt()
        )
        // Marked after showing, so a crash mid-send retries rather than
        // silently swallowing the one nudge this commitment gets.
        MemoryRepository.markReminderSent(id)
        return Result.success()
    }

    companion object {
        const val KEY_MEMORY_ID = "memory_id"
    }
}
