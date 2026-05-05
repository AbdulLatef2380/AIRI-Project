package com.airi.assistant.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ErrorMemoryStore — agent error and failure pattern memory.
 *
 * Records errors the agent encounters so it can:
 *  1. Avoid repeating the same failed action in the same context.
 *  2. Build a failure pattern prompt block that warns the LLM of known pitfalls.
 *  3. Surface a debug UI showing historical failures.
 *
 * ## Error categories tracked
 * - `tool_call_failed`   — connector execute returned Failure
 * - `llm_timeout`        — inference timed out
 * - `node_retry_limit`   — UCL node exceeded retry budget
 * - `permission_denied`  — policy gate blocked an action
 * - `parse_error`        — LLM output could not be parsed into a tool call
 * - `recovery_failed`    — recovery path also failed
 *
 * ## Deduplication
 * Errors with the same (category + errorCode) increment a counter rather than
 * creating a new record — prevents log flooding from repeated failures.
 */
class ErrorMemoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ────────────────────────────────────────────────────────────

    data class ErrorRecord(
        val id:           String,
        val timestampMs:  Long,
        val category:     String,
        val errorCode:    String,
        val message:      String,
        val context:      String,
        val occurrences:  Int = 1,
    )

    /**
     * Record or increment an error. Fire-and-forget. Thread-safe.
     *
     * @param category  One of the standard categories above.
     * @param errorCode Stable machine-readable code from the failure source.
     * @param message   Human-readable description. Truncated at 256 chars.
     * @param ctx       Short context (node id, connector id, prompt prefix). Truncated at 80 chars.
     */
    fun recordError(
        category:  String,
        errorCode: String,
        message:   String,
        ctx:       String = "",
    ) {
        scope.launch {
            val all = loadAll().toMutableList()
            val key = "$category::$errorCode"
            val existing = all.indexOfFirst {
                "${it.category}::${it.errorCode}" == key
            }
            if (existing >= 0) {
                val old = all[existing]
                all[existing] = old.copy(
                    occurrences = old.occurrences + 1,
                    timestampMs = System.currentTimeMillis(),
                    message     = message.take(256),
                    context     = ctx.take(80),
                )
                Log.i("AIRI_PROOF", "ERROR_MEMORY_INCREMENT category=$category code=$errorCode occurrences=${all[existing].occurrences}")
            } else {
                val id = "err_${System.currentTimeMillis()}"
                all.add(ErrorRecord(
                    id          = id,
                    timestampMs = System.currentTimeMillis(),
                    category    = category,
                    errorCode   = errorCode,
                    message     = message.take(256),
                    context     = ctx.take(80),
                ))
                Log.i("AIRI_PROOF", "ERROR_MEMORY_NEW id=$id category=$category code=$errorCode")
            }
            if (all.size > MAX_RECORDS) {
                all.sortByDescending { it.occurrences * 1_000_000L + it.timestampMs }
                all.subList(MAX_RECORDS, all.size).clear()
            }
            saveAll(all)
        }
    }

    /**
     * Build a compact prompt block listing recurring error patterns.
     * Only includes errors with [minOccurrences] or more hits.
     * Returns empty string if no significant errors recorded.
     */
    fun buildErrorContext(minOccurrences: Int = 2, maxEntries: Int = 5): String {
        val significant = loadAll()
            .filter { it.occurrences >= minOccurrences }
            .sortedByDescending { it.occurrences }
            .take(maxEntries)
        if (significant.isEmpty()) return ""

        val lines = significant.joinToString("\n") { e ->
            "- [${e.category}/${e.errorCode}] ×${e.occurrences}: ${e.message.take(120)}"
        }
        return """
--- Known failure patterns (avoid repeating) ---
$lines
--- End failure patterns ---
        """.trimIndent()
    }

    fun getAllErrors(): List<ErrorRecord> =
        loadAll().sortedByDescending { it.occurrences }

    fun clearAll() {
        prefs.edit().remove(KEY_ERRORS).apply()
        Log.i("AIRI_PROOF", "ERROR_MEMORY_CLEAR_ALL")
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun loadAll(): List<ErrorRecord> {
        val raw = prefs.getString(KEY_ERRORS, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            raw.split(RECORD_SEP).mapNotNull { record ->
                val p = record.split(FIELD_SEP)
                if (p.size < 6) return@mapNotNull null
                ErrorRecord(
                    id          = p[0],
                    timestampMs = p[1].toLongOrNull() ?: 0L,
                    category    = p[2],
                    errorCode   = p[3],
                    message     = p[4],
                    context     = p[5],
                    occurrences = p.getOrNull(6)?.toIntOrNull() ?: 1,
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(errors: List<ErrorRecord>) {
        val encoded = errors.joinToString(RECORD_SEP) { e ->
            listOf(e.id, e.timestampMs, e.category, e.errorCode,
                   e.message.replace(FIELD_SEP, " ").replace(RECORD_SEP, " "),
                   e.context.replace(FIELD_SEP, " ").replace(RECORD_SEP, " "),
                   e.occurrences).joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_ERRORS, encoded).apply()
    }

    companion object {
        private const val PREFS_NAME  = "airi_error_memory"
        private const val KEY_ERRORS  = "errors"
        private const val RECORD_SEP  = "\u001E"
        private const val FIELD_SEP   = "\u001F"
        private const val MAX_RECORDS = 100
    }
}
