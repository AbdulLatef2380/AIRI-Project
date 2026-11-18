package com.airi.assistant.ai.prompt

import android.content.Context
import com.airi.core.memory.MemoryAdmissionPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-session compression scratchpad.
 *
 * Summaries remain a best-effort compression cache. Facts are a session-local
 * projection, not the source of durable memory; durable writes remain in Room.
 * Each new fact is bound to the exact session and active project and carries the
 * same privacy ceiling and optional Room memory id so deletion cannot resurrect
 * a forgotten fact from this cache.
 */
data class ScopedMemoryFact(
    val content: String,
    val projectId: String,
    val privacyLevel: Int,
    val sourceMemoryId: Long? = null,
    val expiresAtMs: Long = -1L,
)

object MemoryStore {
    private const val PREFS = "airi_session_compress"
    private const val MAX_FACTS = 30
    private const val LEGACY_PRIVACY_LEVEL = 1
    private const val MIN_PRIVACY_LEVEL = 0
    private const val MAX_PRIVACY_LEVEL = 3
    private const val MAX_PROJECT_ID_CHARS = 128
    private val lock = Any()

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun summaryKey(sessionId: String) = "summary::$sessionId"
    private fun factsKey(sessionId: String) = "facts::$sessionId"
    private fun scopedFactsKey(sessionId: String) = "facts_v2::$sessionId"
    private fun coverageKey(sessionId: String) = "covered_through::$sessionId"

    // ── Summary ──────────────────────────────────────────────────────────────

    fun getSummary(ctx: Context, sessionId: String): String =
        prefs(ctx).getString(summaryKey(sessionId), "") ?: ""

    fun setSummary(ctx: Context, sessionId: String, summary: String) {
        prefs(ctx).edit().putString(summaryKey(sessionId), summary.trim()).apply()
    }

    /** Persist the accepted summary and its coverage marker in one preferences transaction. */
    fun setSummaryAndCoverage(ctx: Context, sessionId: String, summary: String, coveredThrough: Int) {
        prefs(ctx).edit()
            .putString(summaryKey(sessionId), summary.trim())
            .putInt(coverageKey(sessionId), coveredThrough.coerceAtLeast(0))
            .apply()
    }

    fun getSummaryCoverage(ctx: Context, sessionId: String): Int =
        prefs(ctx).getInt(coverageKey(sessionId), 0)

    fun setSummaryCoverage(ctx: Context, sessionId: String, coveredThrough: Int) {
        prefs(ctx).edit().putInt(coverageKey(sessionId), coveredThrough.coerceAtLeast(0)).apply()
    }

    fun clearSummary(ctx: Context, sessionId: String) {
        prefs(ctx).edit()
            .remove(summaryKey(sessionId))
            .remove(coverageKey(sessionId))
            .apply()
    }

    // ── Memory facts ─────────────────────────────────────────────────────────

    /**
     * Compatibility accessor. Unscoped callers can see only session facts for
     * the default (empty-project) scope and the specified privacy ceiling.
     */
    fun getFacts(ctx: Context, sessionId: String): List<String> =
        getScopedFacts(ctx, sessionId, projectId = "", maxPrivacyLevel = LEGACY_PRIVACY_LEVEL)
            .map(ScopedMemoryFact::content)

    /**
     * Read facts for one exact session + project pair. Legacy string arrays did
     * not carry a project id, so they are eligible only in the empty-project
     * scope. All returned facts pass the current strict admission allow-list.
     */
    fun getScopedFacts(
        ctx: Context,
        sessionId: String,
        projectId: String,
        maxPrivacyLevel: Int,
        limit: Int = MAX_FACTS,
        nowMs: Long = System.currentTimeMillis(),
    ): List<ScopedMemoryFact> {
        if (sessionId.isBlank() || limit <= 0) return emptyList()
        val safeProjectId = normalizeProjectId(projectId) ?: return emptyList()
        val safePrivacy = maxPrivacyLevel.coerceIn(MIN_PRIVACY_LEVEL, MAX_PRIVACY_LEVEL)
        synchronized(lock) {
            val store = prefs(ctx)
            val scoped = readScopedFacts(store.getString(scopedFactsKey(sessionId), "[]"))
                .asSequence()
                .filter { it.projectId == safeProjectId }
                .filter { it.privacyLevel <= safePrivacy }
                .filter { it.expiresAtMs < 0 || it.expiresAtMs > nowMs }
                .mapNotNull { fact ->
                    MemoryFactAdmissionPolicy.sanitize(fact.content)?.let { fact.copy(content = it) }
                }
                .toList()

            val legacy = if (safeProjectId.isBlank() && LEGACY_PRIVACY_LEVEL <= safePrivacy) {
                readLegacyFacts(store.getString(factsKey(sessionId), "[]"))
                    .mapNotNull { MemoryFactAdmissionPolicy.sanitize(it) }
                    .map { ScopedMemoryFact(it, projectId = "", privacyLevel = LEGACY_PRIVACY_LEVEL) }
            } else {
                emptyList()
            }

            // New scoped entries win over old entries with the same canonical key.
            val seenKeys = mutableSetOf<String>()
            return (scoped + legacy)
                .filter { seenKeys.add(factKey(it.content)) }
                .take(limit.coerceAtMost(MAX_FACTS))
        }
    }

    /**
     * Merge validated canonical facts. Updates are serialized in-process to
     * avoid SharedPreferences read/modify/write races. Newer values win only
     * within the same session and exact project scope.
     */
    fun mergeFacts(
        ctx: Context,
        sessionId: String,
        newFacts: List<String>,
        projectId: String = "",
        privacyLevel: Int = LEGACY_PRIVACY_LEVEL,
        sourceMemoryId: Long? = null,
        expiresAtMs: Long = -1L,
    ) {
        if (sessionId.isBlank() || newFacts.isEmpty()) return
        val safeProjectId = normalizeProjectId(projectId) ?: return
        val safePrivacy = privacyLevel.coerceIn(MIN_PRIVACY_LEVEL, MAX_PRIVACY_LEVEL)
        val safeFacts = newFacts.mapNotNull(MemoryFactAdmissionPolicy::sanitize).distinct()
        if (safeFacts.isEmpty()) return

        synchronized(lock) {
            val store = prefs(ctx)
            val current = readScopedFacts(store.getString(scopedFactsKey(sessionId), "[]")).toMutableList()
            for (fact in safeFacts) {
                val key = factKey(fact)
                current.removeAll { it.projectId == safeProjectId && factKey(it.content) == key }
                current.add(
                    ScopedMemoryFact(
                        content = fact,
                        projectId = safeProjectId,
                        privacyLevel = safePrivacy,
                        sourceMemoryId = sourceMemoryId,
                        expiresAtMs = expiresAtMs,
                    )
                )
            }
            val array = JSONArray()
            current.takeLast(MAX_FACTS).forEach { fact ->
                array.put(
                    JSONObject()
                        .put("content", fact.content)
                        .put("projectId", fact.projectId)
                        .put("privacyLevel", fact.privacyLevel)
                        .put("sourceMemoryId", fact.sourceMemoryId)
                        .put("expiresAtMs", fact.expiresAtMs)
                )
            }
            store.edit().putString(scopedFactsKey(sessionId), array.toString()).apply()
        }
    }

    /** Drop a mirrored fact when its owning durable Room memory is removed. */
    fun removeSourceMemory(ctx: Context, sessionId: String, memoryId: Long) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            val store = prefs(ctx)
            val remaining = readScopedFacts(store.getString(scopedFactsKey(sessionId), "[]"))
                .filterNot { it.sourceMemoryId == memoryId }
            writeScopedFacts(store, sessionId, remaining)
        }
    }

    fun removeMatchingFact(ctx: Context, sessionId: String, projectId: String, content: String) {
        if (sessionId.isBlank()) return
        val fact = MemoryFactAdmissionPolicy.sanitize(content.removePrefix("[memory] ")) ?: return
        val safeProjectId = normalizeProjectId(projectId) ?: return
        synchronized(lock) {
            val store = prefs(ctx)
            val scoped = readScopedFacts(store.getString(scopedFactsKey(sessionId), "[]"))
                .filterNot { it.projectId == safeProjectId && it.content == fact }
            writeScopedFacts(store, sessionId, scoped)

            if (safeProjectId.isBlank()) {
                val legacy = readLegacyFacts(store.getString(factsKey(sessionId), "[]"))
                    .filterNot { MemoryFactAdmissionPolicy.sanitize(it) == fact }
                val array = JSONArray()
                legacy.forEach(array::put)
                store.edit().putString(factsKey(sessionId), array.toString()).apply()
            }
        }
    }

    /** Clear all scratchpad facts for a session; summaries are intentionally retained. */
    fun clearFacts(ctx: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        synchronized(lock) {
            prefs(ctx).edit()
                .remove(scopedFactsKey(sessionId))
                .remove(factsKey(sessionId))
                .apply()
        }
    }

    fun clearAllFacts(ctx: Context) {
        synchronized(lock) {
            val store = prefs(ctx)
            val editor = store.edit()
            store.all.keys
                .filter { it.startsWith("facts::") || it.startsWith("facts_v2::") }
                .forEach(editor::remove)
            editor.apply()
        }
    }

    fun clear(ctx: Context, sessionId: String) {
        synchronized(lock) {
            prefs(ctx).edit()
                .remove(summaryKey(sessionId))
                .remove(factsKey(sessionId))
                .remove(scopedFactsKey(sessionId))
                .remove(coverageKey(sessionId))
                .apply()
        }
    }

    private fun writeScopedFacts(
        store: android.content.SharedPreferences,
        sessionId: String,
        facts: List<ScopedMemoryFact>,
    ) {
        val array = JSONArray()
        facts.takeLast(MAX_FACTS).forEach { fact ->
            array.put(
                JSONObject()
                    .put("content", fact.content)
                    .put("projectId", fact.projectId)
                    .put("privacyLevel", fact.privacyLevel)
                    .put("sourceMemoryId", fact.sourceMemoryId)
                    .put("expiresAtMs", fact.expiresAtMs)
            )
        }
        store.edit().putString(scopedFactsKey(sessionId), array.toString()).apply()
    }

    private fun readScopedFacts(raw: String?): List<ScopedMemoryFact> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val content = item.optString("content").takeIf(String::isNotBlank) ?: continue
                val projectId = normalizeProjectId(item.optString("projectId")) ?: continue
                val privacy = item.optInt("privacyLevel", LEGACY_PRIVACY_LEVEL)
                    .coerceIn(MIN_PRIVACY_LEVEL, MAX_PRIVACY_LEVEL)
                val sourceId = item.optLong("sourceMemoryId", -1L).takeIf { it > 0L }
                val expiry = item.optLong("expiresAtMs", -1L)
                add(ScopedMemoryFact(content, projectId, privacy, sourceId, expiry))
            }
        }
    }

    private fun readLegacyFacts(raw: String?): List<String> {
        val array = runCatching { JSONArray(raw ?: "[]") }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
    }

    private fun normalizeProjectId(projectId: String): String? {
        val normalized = projectId.trim()
        if (normalized.length > MAX_PROJECT_ID_CHARS || normalized.any(Char::isISOControl)) return null
        return normalized
    }

    private fun factKey(content: String): String = content.substringBefore('=').trim().lowercase()
}

/**
 * Scratchpad facts are a small safe allow-list; durable storage is governed by
 * the richer core MemoryAdmissionPolicy and remains the source of truth.
 */
internal object MemoryFactAdmissionPolicy {
    private val allowedKeys = setOf("preference", "dislike", "language", "project")

    fun sanitize(raw: String): String? {
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank() || normalized.length > 240) return null
        val separator = normalized.indexOf('=')
        if (separator <= 0 || separator == normalized.lastIndex) return null
        val key = normalized.substring(0, separator).trim().lowercase()
        val value = normalized.substring(separator + 1).trim()
        if (key !in allowedKeys) return null
        val canonical = "$key=$value"
        if (!MemoryAdmissionPolicy.allowExtractedFact(canonical)) return null
        return canonical
    }
}
