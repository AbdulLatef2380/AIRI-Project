package com.airi.assistant.ai.prompt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-session compression scratchpad.
 *
 * Stores, per sessionId:
 *   - conversation_summary  : a compact natural-language summary of older turns
 *   - memory_facts          : key user facts ("name=Karim", "language=ar", …)
 *
 * SharedPrefs only — zero schema migrations. The data is best-effort and may
 * be cleared at any time without breaking the conversation; the live
 * KV cache is the source of truth for the immediate context.
 */
object MemoryStore {
    private const val PREFS = "airi_session_compress"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun summaryKey(sessionId: String) = "summary::$sessionId"
    private fun factsKey  (sessionId: String) = "facts::$sessionId"
    private fun coverageKey(sessionId: String) = "covered_through::$sessionId"

    // ── Summary ──────────────────────────────────────────────────────────────

    fun getSummary(ctx: Context, sessionId: String): String =
        prefs(ctx).getString(summaryKey(sessionId), "") ?: ""

    fun setSummary(ctx: Context, sessionId: String, summary: String) {
        prefs(ctx).edit().putString(summaryKey(sessionId), summary.trim()).apply()
    }

    /**
     * How many of the oldest messages (by index) are already represented in
     * the stored summary. Lets us re-summarize incrementally instead of
     * re-summarizing the whole history every time.
     */
    fun getSummaryCoverage(ctx: Context, sessionId: String): Int =
        prefs(ctx).getInt(coverageKey(sessionId), 0)

    fun setSummaryCoverage(ctx: Context, sessionId: String, coveredThrough: Int) {
        prefs(ctx).edit().putInt(coverageKey(sessionId), coveredThrough).apply()
    }

    // ── Memory facts ─────────────────────────────────────────────────────────

    fun getFacts(ctx: Context, sessionId: String): List<String> {
        val raw = prefs(ctx).getString(factsKey(sessionId), "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }

    /**
     * Merge new facts into the stored set (de-duplicated, capped at MAX_FACTS).
     * Newer facts win on key collision (heuristic: same prefix before '=').
     */
    fun mergeFacts(ctx: Context, sessionId: String, newFacts: List<String>) {
        if (newFacts.isEmpty()) return
        val current = getFacts(ctx, sessionId).toMutableList()
        for (f in newFacts) {
            val key = f.substringBefore('=', missingDelimiterValue = f).trim()
            current.removeAll { it.substringBefore('=', missingDelimiterValue = it).trim() == key }
            current.add(f.trim())
        }
        val trimmed = current.takeLast(MAX_FACTS)
        val arr = JSONArray()
        for (f in trimmed) arr.put(f)
        prefs(ctx).edit().putString(factsKey(sessionId), arr.toString()).apply()
    }

    fun clear(ctx: Context, sessionId: String) {
        prefs(ctx).edit()
            .remove(summaryKey(sessionId))
            .remove(factsKey(sessionId))
            .remove(coverageKey(sessionId))
            .apply()
    }

    private const val MAX_FACTS = 30
}
