package com.airi.assistant.connector.local

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * CalendarConnector — read and write Android Calendar events via ContentResolver.
 *
 * ## Supported actions
 * | action            | required params                                       | notes                          |
 * |-------------------|-------------------------------------------------------|--------------------------------|
 * | `list_events`     | `start_ms`, `end_ms` (optional)                       | Returns events in time range   |
 * | `get_event`       | `event_id`                                            | Single event details           |
 * | `create_event`    | `title`, `start_ms`, `end_ms`, `description`, `location` | Creates calendar event      |
 * | `delete_event`    | `event_id`                                            | Removes an event               |
 * | `list_calendars`  | —                                                     | Lists available calendars      |
 *
 * Requires: `READ_CALENDAR` (and `WRITE_CALENDAR` for create/delete).
 * The connector returns Failure with code `permission_denied` if the
 * required permission is not granted — the agent should request it.
 */
class CalendarConnector(private val context: Context) : Connector {

    override val id          = "calendar"
    override val name        = "Calendar"
    override val description = "Read and create Android Calendar events"
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(ConnectorState(connected = false))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("calendar", "events", "scheduling", "reminders"))

    override suspend fun connect(): ConnectorState {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val s = ConnectorState(
            connected  = granted,
            statusLine = if (granted) "Calendar access granted" else "READ_CALENDAR permission required"
        )
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false)
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            when (input.action) {
                "list_events"    -> listEvents(input.params)
                "get_event"      -> getEvent(input.params)
                "create_event"   -> createEvent(input.params)
                "delete_event"   -> deleteEvent(input.params)
                "list_calendars" -> listCalendars()
                else -> ConnectorOutput.Failure("unknown_action",
                    "CalendarConnector does not support action: ${input.action}")
            }
        } catch (e: SecurityException) {
            ConnectorOutput.Failure("permission_denied",
                "Calendar permission not granted: ${e.message}", retryable = false)
        } catch (e: Exception) {
            Log.e(TAG, "CalendarConnector error: ${e.message}")
            ConnectorOutput.Failure("calendar_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private fun listEvents(params: Map<String, String>): ConnectorOutput {
        val now      = System.currentTimeMillis()
        val startMs  = params["start_ms"]?.toLongOrNull() ?: now
        val endMs    = params["end_ms"]?.toLongOrNull() ?: (now + 7L * 24 * 60 * 60 * 1000)

        val uri = CalendarContract.Events.CONTENT_URI
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.ALL_DAY
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
        val selArgs   = arrayOf(startMs.toString(), endMs.toString())
        val sortOrder = "${CalendarContract.Events.DTSTART} ASC"

        val cr     = context.contentResolver
        val cursor = cr.query(uri, projection, selection, selArgs, sortOrder)
            ?: return ConnectorOutput.Failure("query_failed", "Calendar query returned null")

        val events = JSONArray()
        val fmt    = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fmt.timeZone = TimeZone.getDefault()

        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("id",          it.getLong(0))
                obj.put("title",       it.getString(1) ?: "(No title)")
                obj.put("start",       fmt.format(Date(it.getLong(2))))
                obj.put("end",         if (it.isNull(3)) "N/A" else fmt.format(Date(it.getLong(3))))
                obj.put("description", it.getString(4) ?: "")
                obj.put("location",    it.getString(5) ?: "")
                obj.put("all_day",     it.getInt(6) == 1)
                events.put(obj)
            }
        }
        return ConnectorOutput.Success(
            text = "Found ${events.length()} events",
            data = mapOf("events_json" to events.toString(), "count" to events.length().toString())
        )
    }

    private fun getEvent(params: Map<String, String>): ConnectorOutput {
        val id = params["event_id"]
            ?: return ConnectorOutput.Failure("missing_param", "event_id is required")
        val uri  = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, id)
        val proj = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.EVENT_LOCATION
        )
        val cursor = context.contentResolver.query(uri, proj, null, null, null)
            ?: return ConnectorOutput.Failure("not_found", "Event $id not found")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return cursor.use {
            if (!it.moveToFirst()) return ConnectorOutput.Failure("not_found", "Event $id not found")
            val obj = JSONObject()
            obj.put("id",          it.getLong(0))
            obj.put("title",       it.getString(1) ?: "")
            obj.put("start",       fmt.format(Date(it.getLong(2))))
            obj.put("end",         if (it.isNull(3)) "N/A" else fmt.format(Date(it.getLong(3))))
            obj.put("description", it.getString(4) ?: "")
            obj.put("location",    it.getString(5) ?: "")
            ConnectorOutput.Success(text = obj.toString(2), data = mapOf("event_json" to obj.toString()))
        }
    }

    private fun createEvent(params: Map<String, String>): ConnectorOutput {
        val writeGranted = context.checkSelfPermission(android.Manifest.permission.WRITE_CALENDAR) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!writeGranted) return ConnectorOutput.Failure("permission_denied",
            "WRITE_CALENDAR permission required to create events")

        val title       = params["title"]    ?: return ConnectorOutput.Failure("missing_param", "title required")
        val startMs     = params["start_ms"]?.toLongOrNull()
            ?: return ConnectorOutput.Failure("missing_param", "start_ms required")
        val endMs       = params["end_ms"]?.toLongOrNull() ?: (startMs + 60 * 60 * 1000L)
        val description = params["description"] ?: ""
        val location    = params["location"]    ?: ""

        val cv = ContentValues().apply {
            put(CalendarContract.Events.TITLE,          title)
            put(CalendarContract.Events.DTSTART,        startMs)
            put(CalendarContract.Events.DTEND,          endMs)
            put(CalendarContract.Events.DESCRIPTION,    description)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
            ?: return ConnectorOutput.Failure("create_failed", "Failed to create calendar event")

        val newId = uri.lastPathSegment ?: "unknown"
        Log.i(TAG, "Event created id=$newId title=$title")
        return ConnectorOutput.Success(
            text = "Event '$title' created (id=$newId)",
            data = mapOf("event_id" to newId)
        )
    }

    private fun deleteEvent(params: Map<String, String>): ConnectorOutput {
        val id = params["event_id"]
            ?: return ConnectorOutput.Failure("missing_param", "event_id required")
        val uri     = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, id)
        val deleted = context.contentResolver.delete(uri, null, null)
        return if (deleted > 0) ConnectorOutput.Success("Event $id deleted")
        else ConnectorOutput.Failure("not_found", "Event $id not found or could not be deleted")
    }

    private fun listCalendars(): ConnectorOutput {
        val uri  = CalendarContract.Calendars.CONTENT_URI
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.NAME,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        val cursor = context.contentResolver.query(uri, proj, null, null, null)
            ?: return ConnectorOutput.Failure("query_failed", "Could not query calendars")

        val cals = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                val obj = JSONObject()
                obj.put("id",      it.getLong(0))
                obj.put("name",    it.getString(1) ?: "")
                obj.put("display", it.getString(2) ?: "")
                obj.put("account", it.getString(3) ?: "")
                cals.put(obj)
            }
        }
        return ConnectorOutput.Success(
            text = "${cals.length()} calendars found",
            data = mapOf("calendars_json" to cals.toString())
        )
    }

    companion object { private const val TAG = "AIRI_CalendarConnector" }
}
