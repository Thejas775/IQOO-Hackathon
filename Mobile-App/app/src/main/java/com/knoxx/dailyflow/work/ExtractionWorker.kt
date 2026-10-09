package com.knoxx.dailyflow.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.knoxx.dailyflow.debug.DebugLog
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs extraction as a **foreground service**.
 *
 * The promotion is the entire point. An identical worker without
 * `setForeground` leaves the process at background priority, and loading a
 * multi-gigabyte model from there triggered a system-wide low-memory event:
 * the platform killed WebView, TTS, Messages, Settings and nine other
 * processes, then killed this one, and WorkManager restarted it into the same
 * wall. Promoted, the process is protected and the system makes room by
 * evicting other things instead.
 *
 * One pass is load, drain, unload — see [ExtractionRunner]. At roughly 30
 * seconds of load against a 15-minute period, the app is expensive for about
 * 3% of the time and free for the rest.
 */
class ExtractionWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo() =
        ExtractionNotification.foregroundInfo(applicationContext, 0, 0)

    override suspend fun doWork(): Result = mutex.withLock {
        try {
            setForeground(getForegroundInfo())
        } catch (t: Throwable) {
            // Promotion can be refused — no notification permission, or the
            // OS declining a foreground start from the background. Running
            // anyway would repeat the crash this class exists to prevent, so
            // the pass is skipped and retried later instead.
            Log.w(TAG, "Could not promote to foreground; skipping this pass", t)
            DebugLog.log("extract", "pass SKIPPED: foreground promotion refused (${t.javaClass.simpleName})")
            return@withLock Result.retry()
        }

        try {
            ExtractionRunner.runOnce(applicationContext) { done, total ->
                // Keeps the notification honest during a long pass, and keeps
                // the service visibly alive so the OS does not reclaim it.
                runCatching {
                    setForeground(ExtractionNotification.foregroundInfo(applicationContext, done, total))
                }
            }
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Extraction pass failed", t)
            DebugLog.log("extract", "pass FAILED: ${t.javaClass.simpleName}: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ExtractionWorker"

        /** The periodic pass and the manual button are separate work names and
         *  can overlap. Without this both would fetch the same pending rows and
         *  run the same inference twice, competing for the single engine lock. */
        private val mutex = Mutex()
    }
}
