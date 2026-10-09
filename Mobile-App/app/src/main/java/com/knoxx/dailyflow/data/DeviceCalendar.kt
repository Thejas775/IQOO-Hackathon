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
    val allDay: Boolean
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
                CalendarContract.Events.ALL_DAY
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
                        events.add(
                            CalendarEvent(
                                id = cursor.getLong(0),
                                title = title,
                                startAt = cursor.getLong(2),
                                allDay = cursor.getInt(3) == 1
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
}
