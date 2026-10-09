package com.knoxx.dailyflow.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Date and time strings, in the forms the design actually uses.
 *
 * All formatters take the default locale rather than forcing English, so the
 * app follows the device — with the deliberate exception of the timeline's
 * 24-hour gutter, which is fixed-width by design and must not reflow.
 */
object DateLabels {

    private val eyebrowDate = SimpleDateFormat("EEE · d MMM", Locale.getDefault())
    private val hourMinute = SimpleDateFormat("h:mm", Locale.getDefault())
    private val amPm = SimpleDateFormat("a", Locale.getDefault())
    private val dayAndDate = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault())
    private val monthYear = SimpleDateFormat("MMMM", Locale.getDefault())
    private val shortTime = SimpleDateFormat("h:mm a", Locale.getDefault())

    /** "Wed · 20 May" */
    fun eyebrow(millis: Long = System.currentTimeMillis()): String =
        eyebrowDate.format(Date(millis))

    /** "Good morning" — split at the hours people actually use the words. */
    fun greeting(millis: Long = System.currentTimeMillis()): String {
        val hour = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Still up"
        }
    }

    /** Timeline gutter: "9:00" over "AM". */
    fun timelineTime(millis: Long): Pair<String, String> =
        hourMinute.format(Date(millis)) to amPm.format(Date(millis)).uppercase(Locale.getDefault())

    fun time(millis: Long): String = shortTime.format(Date(millis))

    fun dayTitle(millis: Long): String = dayAndDate.format(Date(millis))

    fun month(millis: Long): String = monthYear.format(Date(millis))

    /**
     * How a due date reads on a card: "Due Friday" while it is still this
     * week, an explicit date beyond that, and "Overdue" once it has passed.
     *
     * Weekday names are only unambiguous for about a week — "Due Friday" for
     * something three weeks out actively misleads, which is why this falls
     * back to a date rather than always naming the day.
     */
    fun dueLabel(dueAt: Long?, now: Long = System.currentTimeMillis()): String? {
        dueAt ?: return null
        val days = daysBetween(now, dueAt)
        return when {
            days < 0 -> "Overdue"
            days == 0 -> "Due today"
            days == 1 -> "Due tomorrow"
            days in 2..6 -> "Due " + SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(dueAt))
            else -> "Due " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(dueAt))
        }
    }

    /** "3d", "2w" — the Person screen's "last talked". */
    fun relativeShort(millis: Long?, now: Long = System.currentTimeMillis()): String {
        millis ?: return "—"
        val days = daysBetween(millis, now)
        return when {
            days <= 0 -> "today"
            days == 1 -> "1d"
            days < 7 -> "${days}d"
            days < 30 -> "${days / 7}w"
            days < 365 -> "${days / 30}mo"
            else -> "${days / 365}y"
        }
    }

    /** Whole calendar days between two instants, in device-local time.
     *  Compared at midnight rather than by dividing the millisecond gap, so
     *  "tomorrow" means the next calendar day and not 24 hours from now. */
    private fun daysBetween(from: Long, to: Long): Int {
        val a = midnight(from)
        val b = midnight(to)
        return ((b - a) / 86_400_000L).toInt()
    }

    private fun midnight(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
