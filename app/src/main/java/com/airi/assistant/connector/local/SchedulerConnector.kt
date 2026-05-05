package com.airi.assistant.connector.local

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
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

/**
 * SchedulerConnector — schedule one-shot and repeating alarms via [AlarmManager].
 *
 * The agent uses this to set future reminders, delayed actions, and recurring
 * tasks. All scheduled alarms fire a broadcast that the app receives and logs
 * via AIRI_PROOF. No background process is required — Android AlarmManager
 * wakes the app if needed.
 *
 * ## Supported actions
 * | action           | required params                          | notes                           |
 * |------------------|------------------------------------------|---------------------------------|
 * | `schedule_once`  | `at_ms` or `delay_ms`, `label`, `tag`   | One-shot alarm                  |
 * | `schedule_repeat`| `at_ms`, `interval_ms`, `label`, `tag`  | Repeating alarm                 |
 * | `cancel`         | `tag`                                    | Cancel by tag                   |
 * | `list_pending`   | —                                        | List stored pending alarms      |
 * | `current_time`   | —                                        | Returns current epoch ms        |
 */
class SchedulerConnector(
    private val appContext: Context,
) : Connector {

    override val id          = "scheduler"
    override val name        = "Scheduler"
    override val description = "Schedule one-shot and repeating alarms via Android AlarmManager."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    private val prefs by lazy {
        appContext.getSharedPreferences("airi_scheduler", Context.MODE_PRIVATE)
    }

    private val alarmManager get() =
        appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("alarm", "schedule", "timer", "reminder", "repeat"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            alarmManager.canScheduleExactAlarms() else true
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = if (canExact) "AlarmManager ready (exact alarms allowed)"
                         else "AlarmManager ready (inexact — exact alarm permission not granted)",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (input.action) {
            "schedule_once"   -> scheduleOnce(input)
            "schedule_repeat" -> scheduleRepeat(input)
            "cancel"          -> cancelAlarm(input.params["tag"].orEmpty())
            "list_pending"    -> listPending()
            "current_time"    -> currentTime()
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "SchedulerConnector: unknown action '${input.action}'",
            )
        }
    }

    private fun scheduleOnce(input: ConnectorInput): ConnectorOutput {
        val label = input.params["label"].orEmpty().ifBlank { "AIRI alarm" }
        val tag   = input.params["tag"].orEmpty().ifBlank { "alarm_${System.currentTimeMillis()}" }
        val atMs  = input.params["at_ms"]?.toLongOrNull()
            ?: (System.currentTimeMillis() + (input.params["delay_ms"]?.toLongOrNull() ?: 60_000L))

        if (atMs <= System.currentTimeMillis()) {
            return ConnectorOutput.Failure(
                code = "bad_time",
                message = "at_ms=${atMs} is in the past (now=${System.currentTimeMillis()})",
            )
        }

        val pi = buildPendingIntent(tag, label, requestCode = tag.hashCode())
        scheduleAlarm(atMs, pi)
        storePending(tag, label, atMs, repeat = false, intervalMs = 0)

        val humanTime = dateFormat.format(Date(atMs))
        Log.i("AIRI_PROOF", "SCHEDULER_SET_ONCE tag=$tag label='$label' at=$atMs ($humanTime)")
        return ConnectorOutput.Success(
            text = "Scheduled '$label' for $humanTime",
            data = mapOf("tag" to tag, "at_ms" to atMs.toString(), "human_time" to humanTime),
        )
    }

    private fun scheduleRepeat(input: ConnectorInput): ConnectorOutput {
        val label      = input.params["label"].orEmpty().ifBlank { "AIRI repeating" }
        val tag        = input.params["tag"].orEmpty().ifBlank { "repeat_${System.currentTimeMillis()}" }
        val atMs       = input.params["at_ms"]?.toLongOrNull() ?: System.currentTimeMillis() + 60_000L
        val intervalMs = input.params["interval_ms"]?.toLongOrNull() ?: AlarmManager.INTERVAL_HOUR

        if (intervalMs < 60_000L) {
            return ConnectorOutput.Failure(
                code = "interval_too_short",
                message = "Minimum repeat interval is 60,000 ms (60s). Requested: ${intervalMs}ms",
            )
        }

        val pi = buildPendingIntent(tag, label, requestCode = tag.hashCode())
        alarmManager.setInexactRepeating(AlarmManager.RTC_WAKEUP, atMs, intervalMs, pi)
        storePending(tag, label, atMs, repeat = true, intervalMs = intervalMs)

        val humanTime = dateFormat.format(Date(atMs))
        Log.i("AIRI_PROOF", "SCHEDULER_SET_REPEAT tag=$tag interval=${intervalMs}ms first=$humanTime")
        return ConnectorOutput.Success(
            text = "Scheduled repeating '$label' starting $humanTime every ${intervalMs / 1000}s",
            data = mapOf("tag" to tag, "at_ms" to atMs.toString(), "interval_ms" to intervalMs.toString()),
        )
    }

    private fun cancelAlarm(tag: String): ConnectorOutput {
        if (tag.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'tag' param")
        val pi = buildPendingIntent(tag, "", requestCode = tag.hashCode())
        alarmManager.cancel(pi)
        removePending(tag)
        Log.i("AIRI_PROOF", "SCHEDULER_CANCEL tag=$tag")
        return ConnectorOutput.Success(text = "Cancelled alarm: $tag", data = mapOf("tag" to tag))
    }

    private fun listPending(): ConnectorOutput {
        val raw  = prefs.getString("pending", "[]") ?: "[]"
        val arr  = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val now  = System.currentTimeMillis()
        val items = (0 until arr.length()).mapNotNull { i ->
            val obj = runCatching { arr.getJSONObject(i) }.getOrNull() ?: return@mapNotNull null
            val atMs = obj.optLong("at_ms", 0L)
            val tag  = obj.optString("tag")
            val lbl  = obj.optString("label")
            val rep  = obj.optBoolean("repeat")
            val status = if (atMs > now) "pending in ${(atMs - now) / 1000}s" else "overdue"
            "$tag — '$lbl' @ ${dateFormat.format(Date(atMs))} [$status${if (rep) ", repeating" else ""}]"
        }
        return ConnectorOutput.Success(
            text = if (items.isEmpty()) "No pending alarms." else items.joinToString("\n"),
            data = mapOf("count" to items.size.toString()),
        )
    }

    private fun currentTime(): ConnectorOutput {
        val now = System.currentTimeMillis()
        return ConnectorOutput.Success(
            text = "Current time: ${dateFormat.format(Date(now))} (epoch=$now)",
            data = mapOf("epoch_ms" to now.toString(), "human" to dateFormat.format(Date(now))),
        )
    }

    private fun buildPendingIntent(tag: String, label: String, requestCode: Int): PendingIntent {
        val intent = Intent("com.airi.assistant.ALARM_FIRED").apply {
            setPackage(appContext.packageName)
            putExtra("tag", tag)
            putExtra("label", label)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(appContext, requestCode, intent, flags)
    }

    private fun scheduleAlarm(atMs: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }

    private fun storePending(tag: String, label: String, atMs: Long, repeat: Boolean, intervalMs: Long) {
        val raw = prefs.getString("pending", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val obj = JSONObject().apply {
            put("tag", tag); put("label", label); put("at_ms", atMs)
            put("repeat", repeat); put("interval_ms", intervalMs)
        }
        arr.put(obj)
        prefs.edit().putString("pending", arr.toString()).apply()
    }

    private fun removePending(tag: String) {
        val raw = prefs.getString("pending", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val updated = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            if (obj.optString("tag") != tag) updated.put(obj)
        }
        prefs.edit().putString("pending", updated.toString()).apply()
    }
}
