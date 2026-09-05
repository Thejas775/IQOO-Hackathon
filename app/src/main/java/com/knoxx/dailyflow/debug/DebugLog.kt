package com.knoxx.dailyflow.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * An on-device trace of what capture and extraction actually did.
 *
 * logcat is not enough here: the interesting failures happen over hours, in
 * the background, while the phone is not plugged in — by the time a mismatch
 * is noticed the buffer holding its cause has long since rolled. This survives
 * reboots and can be pulled afterwards.
 *
 * Written to the app's *external* files dir on purpose, so it can be read with
 * a plain `adb pull` and no run-as. Nothing here leaves the device on its own.
 *
 * Wick had an equivalent and it was a genuine performance problem: it opened,
 * wrote, flushed and closed the file twice per event, on the main thread, at
 * roughly 8,700 events a day. This takes the opposite shape — callers hand a
 * line to a channel and never block, and exactly one coroutine owns the file.
 */
object DebugLog {

    private const val TAG = "DebugLog"
    private const val FILE_NAME = "dailyflow-trace.log"

    /** Rotated rather than unbounded: this runs all day on someone's phone. */
    private const val MAX_BYTES = 4L * 1024 * 1024

    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // UNLIMITED so that a caller on the accessibility callback can never be
    // suspended by logging — dropping the odd line would be better than
    // stalling capture, but not blocking at all is better still.
    private val lines = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var file: File? = null
    @Volatile private var started = false

    fun init(context: Context) {
        if (started) return
        started = true

        val dir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        val target = File(dir, FILE_NAME)
        file = target

        scope.launch {
            for (line in lines) {
                try {
                    if (target.length() > MAX_BYTES) rotate(target)
                    target.appendText(line + "\n")
                } catch (t: Throwable) {
                    // Never let tracing take down the thing it is tracing.
                    Log.w(TAG, "Could not write trace line", t)
                }
            }
        }
    }

    /** One generation back only. Two files bound the disk cost; the older one
     *  is usually still the interesting one when a bug is noticed late. */
    private fun rotate(target: File) {
        val previous = File(target.parentFile, "$FILE_NAME.1")
        if (previous.exists()) previous.delete()
        target.renameTo(previous)
    }

    /**
     * Records one event. Never blocks and never throws.
     *
     * [tag] is the subsystem — "capture", "extract", "remind" — so a trace can
     * be filtered down to one stage when reading it back.
     */
    fun log(tag: String, message: String) {
        if (!started) return
        lines.trySend("${stamp.format(Date())} [$tag] $message")
    }

    /** Where to pull it from, shown in the UI so it is discoverable. */
    fun path(context: Context): String =
        File(File(context.getExternalFilesDir(null), "logs"), FILE_NAME).absolutePath
}
