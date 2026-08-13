package com.airi.assistant.tools.execution

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * AlarmTool — real alarm creation and management.
 *
 * Two execution modes:
 *
 *   1. [setAlarmViaIntent] — uses AlarmClock.ACTION_SET_ALARM / ACTION_SET_TIMER.
 *      Requires no special permissions. Opens the system clock app.
 *      The most reliable cross-device approach.
 *
 *   2. [setExactAlarm] — uses AlarmManager.setExactAndAllowWhileIdle.
 *      Requires SCHEDULE_EXACT_ALARM (Android 12+) or falls back to inexact.
 *      Used for background-triggered alarms without opening Clock app.
 *
 * For user-visible one-shot alarms (voice command "set alarm for 7am"),
 * always prefer [setAlarmViaIntent] — it's what the system alarm app uses.
 */
class AlarmTool(private val context: Context) {

    private val TAG = "AlarmTool"

    // ─────────────────────────────────────────────────────────────────────────
    // Intent-based (no permissions required)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Set an alarm using the system clock app (AlarmClock.ACTION_SET_ALARM).
     *
     * @param hour    Hour in 24h format (0–23)
     * @param minute  Minute (0–59)
     * @param message Optional alarm label shown in Clock app
     * @param days    Days of week to repeat (1=Sunday … 7=Saturday). Empty = no repeat.
     */
    fun setAlarmViaIntent(
        hour:    Int,
        minute:  Int,
        message: String = "AIRI Alarm",
        days:    List<Int> = emptyList()
    ): AlarmResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_HOUR,         hour)
                putExtra(AlarmClock.EXTRA_MINUTES,      minute)
                putExtra(AlarmClock.EXTRA_MESSAGE,      message)
                putExtra(AlarmClock.EXTRA_SKIP_UI,      true)
                if (days.isNotEmpty()) {
                    putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                }
            }
            context.startActivity(intent)
            val label = "${padded(hour)}:${padded(minute)}"
            Log.i(TAG, "Alarm set via intent: time=$label messageChars=${message.length}")
            AlarmResult(success = true, label = label, message = "Alarm set for $label")
        } catch (e: Exception) {
            Log.e(TAG, "setAlarmViaIntent failed: ${e.message}")
            AlarmResult(success = false, message = "Failed to set alarm: ${e.message}")
        }
    }

    /**
     * Set a countdown timer using the system clock app (AlarmClock.ACTION_SET_TIMER).
     *
     * @param durationSeconds Timer duration in seconds
     * @param message         Optional timer label
     */
    fun setTimerViaIntent(durationSeconds: Int, message: String = "AIRI Timer"): AlarmResult {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(AlarmClock.EXTRA_LENGTH,   durationSeconds)
                putExtra(AlarmClock.EXTRA_MESSAGE,  message)
                putExtra(AlarmClock.EXTRA_SKIP_UI,  true)
            }
            context.startActivity(intent)
            val mins = durationSeconds / 60
            val secs = durationSeconds % 60
            val label = if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
            Log.i(TAG, "Timer set via intent: $label")
            AlarmResult(success = true, label = label, message = "Timer set for $label")
        } catch (e: Exception) {
            Log.e(TAG, "setTimerViaIntent failed: ${e.message}")
            AlarmResult(success = false, message = "Failed to set timer: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AlarmManager-based (for background-triggered alarms)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Schedule an exact background alarm using AlarmManager.
     * Requires SCHEDULE_EXACT_ALARM on Android 12+ (check before calling).
     *
     * @param triggerAtMs Wall-clock time in milliseconds when alarm should fire.
     * @param requestCode Unique ID for the PendingIntent.
     * @param broadcastIntent The broadcast to fire. Must be a manifest-registered receiver.
     */
    suspend fun setExactAlarm(
        triggerAtMs:     Long,
        requestCode:     Int,
        broadcastIntent: Intent
    ): AlarmResult = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            broadcastIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return@withContext try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
            Log.i(TAG, "Exact alarm scheduled at $triggerAtMs (requestCode=$requestCode)")
            AlarmResult(success = true, message = "Exact alarm scheduled")
        } catch (e: SecurityException) {
            Log.e(TAG, "setExactAlarm SecurityException: ${e.message}")
            AlarmResult(success = false, message = "Permission denied: SCHEDULE_EXACT_ALARM required")
        } catch (e: Exception) {
            Log.e(TAG, "setExactAlarm failed: ${e.message}")
            AlarmResult(success = false, message = "Failed to schedule alarm: ${e.message}")
        }
    }

    /**
     * Cancel a previously set AlarmManager alarm.
     */
    fun cancelAlarm(requestCode: Int, broadcastIntent: Intent): Boolean {
        return try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                requestCode,
                broadcastIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Log.i(TAG, "Alarm cancelled requestCode=$requestCode")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "cancelAlarm failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parse helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Parse natural-language time string into (hour, minute) pair.
     * Supports: "7am", "7:30am", "14:30", "2:30 pm", "noon", "midnight".
     * Returns null if unparseable.
     */
    fun parseTime(input: String): Pair<Int, Int>? {
        val normalized = input.lowercase().trim()
        return when {
            normalized == "noon"     -> 12 to 0
            normalized == "midnight" -> 0  to 0
            else -> {
                val amPm  = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)""").find(normalized)
                val hour24 = Regex("""(\d{1,2}):(\d{2})""").find(normalized)
                when {
                    amPm != null -> {
                        var h = amPm.groupValues[1].toIntOrNull() ?: return null
                        val m = amPm.groupValues[2].toIntOrNull() ?: 0
                        val suffix = amPm.groupValues[3]
                        if (suffix == "pm" && h != 12) h += 12
                        if (suffix == "am" && h == 12) h = 0
                        if (h in 0..23 && m in 0..59) h to m else null
                    }
                    hour24 != null -> {
                        val h = hour24.groupValues[1].toIntOrNull() ?: return null
                        val m = hour24.groupValues[2].toIntOrNull() ?: return null
                        if (h in 0..23 && m in 0..59) h to m else null
                    }
                    else -> null
                }
            }
        }
    }

    /**
     * Parse duration string into seconds.
     * Supports: "5 minutes", "30 seconds", "1 hour", "1h30m", "90s".
     */
    fun parseDuration(input: String): Int? {
        val norm = input.lowercase().trim()
        var seconds = 0
        val hourMatch   = Regex("""(\d+)\s*(?:h|hour|hours)""").find(norm)
        val minuteMatch = Regex("""(\d+)\s*(?:m|min|minute|minutes)""").find(norm)
        val secondMatch = Regex("""(\d+)\s*(?:s|sec|second|seconds)""").find(norm)
        hourMatch?.let   { seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 3600 }
        minuteMatch?.let { seconds += (it.groupValues[1].toIntOrNull() ?: 0) * 60   }
        secondMatch?.let { seconds += (it.groupValues[1].toIntOrNull() ?: 0)         }
        return if (seconds > 0) seconds else null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun padded(n: Int) = n.toString().padStart(2, '0')

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    data class AlarmResult(
        val success: Boolean,
        val label:   String = "",
        val message: String = ""
    )
}
