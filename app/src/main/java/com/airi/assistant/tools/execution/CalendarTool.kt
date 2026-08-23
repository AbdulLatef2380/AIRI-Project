package com.airi.assistant.tools.execution

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CalendarTool — real ContentProvider-backed calendar access.
 *
 * Reads and creates calendar events using Android's CalendarContract provider.
 * All operations are permission-checked before execution and run on Dispatchers.IO.
 *
 * Permissions required: READ_CALENDAR (reads), WRITE_CALENDAR (create/delete).
 */
class CalendarTool(private val context: Context) {

    private val TAG = "CalendarTool"

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun canRead() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    private fun canWrite() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.WRITE_CALENDAR
    ) == PackageManager.PERMISSION_GRANTED

    // ─────────────────────────────────────────────────────────────────────────
    // Read API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch upcoming events for the next [daysAhead] days.
     * Returns an empty list if READ_CALENDAR is not granted.
     */
    suspend fun getUpcomingEvents(daysAhead: Int = 7): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!canRead()) {
            Log.w(TAG, "READ_CALENDAR permission not granted")
            return@withContext emptyList()
        }
        val now   = System.currentTimeMillis()
        val until = now + daysAhead * 24 * 60 * 60 * 1000L

        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selectionArgs = arrayOf("$now", "$until")

        val cursor: Cursor? = try {
            context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Calendar query failed: ${e.message}")
            return@withContext emptyList()
        }

        val events = mutableListOf<CalendarEvent>()
        cursor?.use { c ->
            while (c.moveToNext()) {
                events += CalendarEvent(
                    id           = c.getLong(0),
                    title        = c.getString(1) ?: "Untitled",
                    startMs      = c.getLong(2),
                    endMs        = c.getLong(3),
                    description  = c.getString(4) ?: "",
                    location     = c.getString(5) ?: "",
                    allDay       = c.getInt(6) == 1,
                    calendarName = c.getString(7) ?: ""
                )
            }
        }
        Log.d(TAG, "getUpcomingEvents: ${events.size} events in next $daysAhead days")
        events
    }

    /**
     * Find events matching [query] in title or description.
     */
    suspend fun searchEvents(query: String, limitDays: Int = 30): List<CalendarEvent> =
        getUpcomingEvents(limitDays).filter { event ->
            event.title.contains(query, ignoreCase = true) ||
            event.description.contains(query, ignoreCase = true) ||
            event.location.contains(query, ignoreCase = true)
        }

    /**
     * Get today's events.
     */
    suspend fun getTodayEvents(): List<CalendarEvent> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = cal.timeInMillis
        val endOfDay   = startOfDay + 24 * 60 * 60 * 1000L - 1

        return withContext(Dispatchers.IO) {
            if (!canRead()) return@withContext emptyList()
            getUpcomingEvents(1).filter { it.startMs in startOfDay..endOfDay }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Write API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a new calendar event.
     *
     * @param title       Event title
     * @param startMs     Start time in epoch milliseconds
     * @param durationMs  Duration in milliseconds (default 1 hour)
     * @param description Optional event description
     * @param location    Optional location string
     * @return ID of the created event, or -1 on failure.
     */
    suspend fun createEvent(
        title:       String,
        startMs:     Long,
        durationMs:  Long   = 60 * 60 * 1000L,
        description: String = "",
        location:    String = ""
    ): Long = withContext(Dispatchers.IO) {
        if (!canWrite()) {
            Log.w(TAG, "WRITE_CALENDAR permission not granted")
            return@withContext -1L
        }

        val calendarId = getPrimaryCalendarId() ?: run {
            Log.e(TAG, "No primary calendar found")
            return@withContext -1L
        }

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID,  calendarId)
            put(CalendarContract.Events.TITLE,        title)
            put(CalendarContract.Events.DTSTART,      startMs)
            put(CalendarContract.Events.DTEND,        startMs + durationMs)
            put(CalendarContract.Events.DESCRIPTION,  description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return@withContext try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            val id  = uri?.lastPathSegment?.toLongOrNull() ?: -1L
            if (id > 0L) Log.i(TAG, "Calendar event insert reported success")
            else Log.w(TAG, "Calendar provider did not return an event identifier")
            id
        } catch (e: Exception) {
            Log.e(TAG, "Calendar provider insert failed")
            -1L
        }
    }

    /**
     * Delete an event by ID.
     * @return true if deleted successfully.
     */
    suspend fun deleteEvent(eventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!canWrite()) return@withContext false
        return@withContext try {
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath("$eventId").build()
            val rows = context.contentResolver.delete(uri, null, null)
            Log.i(TAG, "Deleted event id=$eventId rows=$rows")
            rows > 0
        } catch (e: Exception) {
            Log.e(TAG, "deleteEvent failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Format helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Format event list as a human-readable summary for LLM context. */
    fun summarize(events: List<CalendarEvent>): String {
        if (events.isEmpty()) return "No events found."
        return events.joinToString("\n") { e ->
            val time = if (e.allDay) "All day" else "${formatTime(e.startMs)} – ${formatTime(e.endMs)}"
            "• ${e.title}  [$time]${if (e.location.isNotBlank()) "  ${e.location}" else ""}"
        }
    }

    private fun formatTime(ms: Long): String =
        SimpleDateFormat("EEE MMM d, h:mm a", Locale.getDefault()).format(Date(ms))

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun getPrimaryCalendarId(): Long? = withContext(Dispatchers.IO) {
        val projection = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY)
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, projection, null, null, null
        )
        cursor?.use { c ->
            while (c.moveToNext()) {
                val id      = c.getLong(0)
                val primary = c.getInt(1) == 1
                if (primary) return@withContext id
            }
            // Fallback: first calendar
            if (c.moveToFirst()) return@withContext c.getLong(0)
        }
        null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data model
    // ─────────────────────────────────────────────────────────────────────────

    data class CalendarEvent(
        val id:           Long,
        val title:        String,
        val startMs:      Long,
        val endMs:        Long,
        val description:  String,
        val location:     String,
        val allDay:       Boolean,
        val calendarName: String
    )
}
