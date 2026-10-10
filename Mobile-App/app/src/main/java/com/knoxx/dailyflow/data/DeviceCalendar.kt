package com.knoxx.dailyflow.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An event read from the device's own calendar, not from screen capture. */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startAt: Long,
    val allDay: Boolean,
    /**
     * When it ends. Needed to answer "am I free at 3?" — a start time alone
     * cannot tell you whether the 2 o'clock is still running.
     *
     * Defaults to an hour past the start, because DTEND is genuinely null for
     * recurring events (they carry DURATION instead) and treating those as
     * instantaneous would report the owner free in the middle of a standup.
     */
    val endAt: Long = startAt + 60 * 60 * 1000L
)

/**
 * Reads the device calendar so the Calendar tab shows a real month rather than
 * only what DailyFlow happened to overhear.
 *
 * Read-only, and never written back. DailyFlow does not create events — a
 * memory app that silently adds things to someone's calendar would be a
 * different and much more intrusive product.
 *
 * Nothing here is stored. Events are queried for the visible range and
 * discarded, so the calendar stays the system's data rather than becoming a
 * second copy inside this app's database that could drift out of date.
 */
object DeviceCalendar {

    private const val TAG = "DeviceCalendar"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Events starting within [start, end). Returns empty when permission has
     * not been granted — the calendar merge is an enhancement, so its absence
     * degrades the screen rather than blocking it.
     */
    suspend fun eventsBetween(context: Context, start: Long, end: Long): List<CalendarEvent> =
        withContext(Dispatchers.IO) {
            if (!hasPermission(context)) return@withContext emptyList()

            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.ALL_DAY,
                CalendarContract.Events.DTEND
            )

            // Deleted events remain in the provider with a flag set, so they
            // are filtered here rather than showing as ghosts.
            val selection =
                "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ? " +
                    "AND ${CalendarContract.Events.DELETED} = 0"

            val events = mutableListOf<CalendarEvent>()
            try {
                context.contentResolver.query(
                    CalendarContract.Events.CONTENT_URI,
                    projection,
                    selection,
                    arrayOf(start.toString(), end.toString()),
                    "${CalendarContract.Events.DTSTART} ASC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val title = cursor.getString(1)?.trim()
                        // An untitled event carries no information for the
                        // user, so it is skipped rather than rendered blank.
                        if (title.isNullOrEmpty()) continue
                        val startAt = cursor.getLong(2)
                        events.add(
                            CalendarEvent(
                                id = cursor.getLong(0),
                                title = title,
                                startAt = startAt,
                                allDay = cursor.getInt(3) == 1,
                                // Null for recurring events, and zero shows up
                                // in the wild too; either would make the event
                                // look instantaneous, so both fall back to the
                                // one-hour default.
                                endAt = cursor.getLong(4)
                                    .takeIf { it > startAt }
                                    ?: (startAt + 60 * 60 * 1000L)
                            )
                        )
                    }
                }
            } catch (e: SecurityException) {
                // Permission can be revoked while the app is running.
                Log.w(TAG, "Calendar read denied", e)
            } catch (t: Throwable) {
                Log.e(TAG, "Calendar query failed", t)
            }
            events
        }

    /**
     * Whether the owner is free at [at], and when they next are.
     *
     * [known] is the field that matters most. Without the calendar permission
     * this returns known = false and NO conflicts, which is not the same as
     * being free — anything reading this must say nothing about availability
     * rather than assume the day is clear. Drafting "yes, 3 works!" off an
     * unreadable calendar is worse than drafting nothing.
     */
    suspend fun availabilityAt(
        context: Context,
        at: Long,
        durationMs: Long = 60 * 60 * 1000L
    ): Availability {
        if (!hasPermission(context)) return Availability(known = false, at = at)

        val dayStart = startOfDay(at)
        val dayEnd = dayStart + DAY_MS

        // Queried from the previous midnight so a long event that began
        // yesterday evening is still in the window — DTSTART >= dayStart
        // alone would miss exactly the meeting that is overrunning into the
        // slot being asked about.
        val events = eventsBetween(context, dayStart - DAY_MS, dayEnd)
            .filter { !it.allDay }

        val slotEnd = at + durationMs
        val conflicts = events.filter { it.startAt < slotEnd && it.endAt > at }

        return Availability(
            known = true,
            at = at,
            conflicts = conflicts,
            alternatives = if (conflicts.isEmpty()) emptyList() else {
                freeSlots(events, dayStart, at, durationMs)
            }
        )
    }

    /**
     * Up to two free starts on the same day, nearest the requested time first.
     *
     * Bounded to waking hours and to on-the-hour starts, because the point is
     * a time a person would actually propose out loud. "I could do 14:37"
     * is technically free and socially absurd.
     */
    private fun freeSlots(
        events: List<CalendarEvent>,
        dayStart: Long,
        wanted: Long,
        durationMs: Long
    ): List<Long> {
        val candidates = (FIRST_HOUR..LAST_HOUR)
            .map { dayStart + it * 60 * 60 * 1000L }
            .filter { it != wanted }
            .filter { candidate ->
                val end = candidate + durationMs
                events.none { it.startAt < end && it.endAt > candidate }
            }
            // Nearest to the time that was actually asked about, so the
            // counter-offer reads as a small adjustment rather than a
            // different day.
            .sortedBy { kotlin.math.abs(it - wanted) }

        return candidates.take(2).sorted()
    }

    private fun startOfDay(at: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = at
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val FIRST_HOUR = 9
    private const val LAST_HOUR = 20
}

/**
 * What the calendar can say about one moment.
 *
 * Three states, not two: free, busy, and unknown. The third exists because
 * the permission is optional, and a draft that confidently accepts an
 * invitation on an unreadable calendar is the failure this whole type is
 * meant to prevent.
 */
data class Availability(
    val known: Boolean,
    val at: Long,
    val conflicts: List<CalendarEvent> = emptyList(),
    val alternatives: List<Long> = emptyList()
) {
    val free: Boolean get() = known && conflicts.isEmpty()
}
