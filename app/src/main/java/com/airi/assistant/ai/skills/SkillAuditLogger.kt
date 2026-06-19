package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * SkillAuditLogger — persistent, rolling audit trail for every skill execution.
 *
 * Stores the last [MAX_EVENTS] events in SharedPreferences. Each event records:
 *  - which skill ran (id + tool name)
 *  - whether it succeeded
 *  - how long it took (ms)
 *  - any error message
 *  - wall-clock timestamp
 *
 * Used by [CustomSkillExecutor] and [SkillToolBridge] to produce a queryable
 * execution history for diagnostics, rate-limit enforcement, and trust scoring.
 *
 * Thread-safe: all writes are serialised through SharedPreferences.edit().apply().
 */
object SkillAuditLogger {

    private const val TAG       = "SkillAuditLogger"
    private const val PREFS_NAME = "airi_skill_audit"
    private const val KEY_EVENTS = "audit_events_v1"
    private const val MAX_EVENTS = 1_000

    // ── Data model ─────────────────────────────────────────────────────────────

    data class AuditEvent(
        val skillId:     String,
        val toolName:    String,
        val success:     Boolean,
        val durationMs:  Long,
        val timestampMs: Long   = System.currentTimeMillis(),
        val errorMsg:    String? = null,
        val callerNote:  String? = null
    )

    // ── Write ──────────────────────────────────────────────────────────────────

    /**
     * Append an [event] to the audit log.
     * Old entries are automatically trimmed to keep the store under [MAX_EVENTS].
     */
    fun log(context: Context, event: AuditEvent) {
        try {
            val prefs = prefs(context)
            val arr   = loadRaw(prefs)
            arr.put(eventToJson(event))
            val trimmed = if (arr.length() > MAX_EVENTS) trimArray(arr) else arr
            prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write audit event: ${e.message}")
        }
    }

    // ── Read ───────────────────────────────────────────────────────────────────

    /**
     * Return the most recent [limit] events, optionally filtered to [skillId].
     * Results are ordered newest-first.
     */
    fun getEvents(
        context: Context,
        skillId: String? = null,
        limit:   Int     = 100
    ): List<AuditEvent> {
        return try {
            val arr    = loadRaw(prefs(context))
            val result = mutableListOf<AuditEvent>()
            for (i in arr.length() - 1 downTo 0) {
                if (result.size >= limit) break
                val obj = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
                val sid = obj.optString("skill_id")
                if (skillId != null && sid != skillId) continue
                result.add(jsonToEvent(obj))
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read audit events: ${e.message}")
            emptyList()
        }
    }

    /**
     * Returns summary statistics for a skill:
     *  totalCalls, successCount, failureCount, avgDurationMs.
     */
    data class SkillStats(
        val totalCalls:   Int,
        val successCount: Int,
        val failureCount: Int,
        val avgDurationMs: Long
    )

    fun getStats(context: Context, skillId: String): SkillStats {
        val events = getEvents(context, skillId, limit = MAX_EVENTS)
        val successes = events.count { it.success }
        val avgMs     = if (events.isEmpty()) 0L else events.sumOf { it.durationMs } / events.size
        return SkillStats(
            totalCalls    = events.size,
            successCount  = successes,
            failureCount  = events.size - successes,
            avgDurationMs = avgMs
        )
    }

    /** Count calls made by [skillId] within the last [windowMs] milliseconds. */
    fun callsInWindow(context: Context, skillId: String, windowMs: Long): Int {
        val cutoff = System.currentTimeMillis() - windowMs
        return getEvents(context, skillId, limit = MAX_EVENTS)
            .count { it.timestampMs >= cutoff }
    }

    /** Clear all audit events. */
    fun clearEvents(context: Context) {
        prefs(context).edit().remove(KEY_EVENTS).apply()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun loadRaw(prefs: android.content.SharedPreferences): JSONArray {
        val raw = prefs.getString(KEY_EVENTS, null) ?: return JSONArray()
        return runCatching { JSONArray(raw) }.getOrElse { JSONArray() }
    }

    private fun trimArray(arr: JSONArray): JSONArray {
        val start = arr.length() - MAX_EVENTS
        val out   = JSONArray()
        for (i in start until arr.length()) {
            runCatching { out.put(arr.getJSONObject(i)) }
        }
        return out
    }

    private fun eventToJson(e: AuditEvent): JSONObject = JSONObject().apply {
        put("skill_id",     e.skillId)
        put("tool_name",    e.toolName)
        put("success",      e.success)
        put("duration_ms",  e.durationMs)
        put("timestamp_ms", e.timestampMs)
        e.errorMsg?.let    { put("error",       it.take(300)) }
        e.callerNote?.let  { put("caller_note", it.take(100)) }
    }

    private fun jsonToEvent(obj: JSONObject) = AuditEvent(
        skillId     = obj.optString("skill_id"),
        toolName    = obj.optString("tool_name"),
        success     = obj.optBoolean("success"),
        durationMs  = obj.optLong("duration_ms"),
        timestampMs = obj.optLong("timestamp_ms", System.currentTimeMillis()),
        errorMsg    = obj.optString("error").ifBlank { null },
        callerNote  = obj.optString("caller_note").ifBlank { null }
    )
}
