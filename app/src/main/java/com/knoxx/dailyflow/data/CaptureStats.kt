package com.knoxx.dailyflow.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Counters behind the You screen: "269 events · 0 cloud calls" and the
 * hours-on ring.
 *
 * Deliberately *not* a log. Wick wrote every captured event to a JSON Lines
 * file as well as the database, which meant a permanent plaintext copy of
 * everything that had ever been on screen sitting in external storage — and
 * the write happened on the main thread, twice per event, at roughly 8,700
 * events a day. Room already holds the text that matters. All that is needed
 * here is a count, so this keeps a count.
 *
 * The events counter is the number of screens *observed*, which is much larger
 * than the number of memories kept — that gap is the dedup pipeline doing its
 * job, and showing both is what makes the You screen honest.
 */
object CaptureStats {

    private const val PREFS = "dailyflow_capture_stats"
    private const val KEY_EVENTS = "events_seen"
    private const val KEY_SINCE = "capturing_since"

    /** Writes are batched: incrementing SharedPreferences on every
     *  accessibility event would be its own version of the bug this class
     *  exists to avoid. */
    private const val FLUSH_EVERY = 25

    private lateinit var prefs: SharedPreferences
    private var unflushed = 0

    private val _eventsSeen = MutableStateFlow(0L)
    val eventsSeen: StateFlow<Long> = _eventsSeen.asStateFlow()

    private val _capturingSince = MutableStateFlow(0L)
    val capturingSince: StateFlow<Long> = _capturingSince.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _eventsSeen.value = prefs.getLong(KEY_EVENTS, 0L)
        _capturingSince.value = prefs.getLong(KEY_SINCE, 0L)
    }

    /** Called when the service connects, so the ring can show hours-on. */
    fun markCaptureStarted() {
        if (!::prefs.isInitialized) return
        if (_capturingSince.value == 0L) {
            val now = System.currentTimeMillis()
            _capturingSince.value = now
            prefs.edit().putLong(KEY_SINCE, now).apply()
        }
    }

    fun markCaptureStopped() {
        if (!::prefs.isInitialized) return
        _capturingSince.value = 0L
        prefs.edit().putLong(KEY_SINCE, 0L).apply()
    }

    fun recordEvent() {
        if (!::prefs.isInitialized) return
        _eventsSeen.value = _eventsSeen.value + 1
        if (++unflushed >= FLUSH_EVERY) {
            unflushed = 0
            prefs.edit().putLong(KEY_EVENTS, _eventsSeen.value).apply()
        }
    }

    /** Flushes the pending count — called when the service disconnects, so a
     *  few events aren't lost every time capture is toggled. */
    fun flush() {
        if (!::prefs.isInitialized) return
        unflushed = 0
        prefs.edit().putLong(KEY_EVENTS, _eventsSeen.value).apply()
    }

    fun reset() {
        if (!::prefs.isInitialized) return
        unflushed = 0
        _eventsSeen.value = 0L
        prefs.edit().putLong(KEY_EVENTS, 0L).apply()
    }
}
