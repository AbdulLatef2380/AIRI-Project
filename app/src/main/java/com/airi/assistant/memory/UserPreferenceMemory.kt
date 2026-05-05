package com.airi.assistant.memory

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UserPreferenceMemory — infers and stores user preferences from interactions.
 *
 * The agent writes preferences here when it detects patterns in user behaviour —
 * preferred response language, code style, verbosity level, topic interests, etc.
 * These are then injected into the system prompt as a personalisation block.
 *
 * ## Preference categories
 * - `language`        — preferred response language (e.g. "Arabic", "English")
 * - `verbosity`       — "concise" | "balanced" | "detailed"
 * - `code_style`      — "kotlin", "python", "typescript", etc.
 * - `tone`            — "formal", "casual", "technical"
 * - `topic_interest`  — free-form topics the user often asks about
 * - `avoid`           — things the user explicitly asked to avoid
 * - `custom`          — any key-value pair the agent or user sets explicitly
 *
 * ## Confidence
 * Each preference has a confidence score (0.0–1.0). Inferred preferences start
 * at 0.5; explicit user statements start at 1.0. Preferences below
 * [MIN_CONFIDENCE_TO_INJECT] are not injected into the prompt.
 *
 * ## Thread safety
 * All writes are dispatched to [Dispatchers.IO]. Reads are synchronous
 * (SharedPreferences in-memory cache). Safe to call from any thread/coroutine.
 */
class UserPreferenceMemory(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _preferenceCount = MutableStateFlow(0)
    val preferenceCount: StateFlow<Int> = _preferenceCount.asStateFlow()

    init { _preferenceCount.value = loadAll().size }

    // ── Public API ────────────────────────────────────────────────────────────

    data class Preference(
        val key:         String,
        val value:       String,
        val confidence:  Float = 1.0f,
        val updatedMs:   Long  = System.currentTimeMillis(),
        val source:      String = "explicit",
    )

    /**
     * Set or update a preference. Explicit user statements should use
     * confidence=1.0; inferred preferences should use 0.4–0.7.
     *
     * @param key        Stable key (e.g. "language", "verbosity", "code_style")
     * @param value      The value (e.g. "Arabic", "concise", "kotlin")
     * @param confidence 0.0–1.0
     * @param source     "explicit" | "inferred" | "conversation"
     */
    fun setPreference(
        key:        String,
        value:      String,
        confidence: Float  = 1.0f,
        source:     String = "explicit",
    ) {
        scope.launch {
            val all = loadAll().toMutableMap()
            val clamped = confidence.coerceIn(0f, 1f)
            all[key] = Preference(
                key        = key,
                value      = value.take(200),
                confidence = clamped,
                updatedMs  = System.currentTimeMillis(),
                source     = source,
            )
            saveAll(all)
            _preferenceCount.value = all.size
            Log.i("AIRI_PROOF", "USER_PREF_SET key=$key value='${value.take(40)}' conf=$clamped source=$source")
        }
    }

    /**
     * Infer a preference from observed behaviour. If a preference with the
     * same key already exists at higher confidence, it is not overwritten.
     */
    fun inferPreference(key: String, value: String, confidence: Float = 0.5f) {
        scope.launch {
            val all      = loadAll().toMutableMap()
            val existing = all[key]
            if (existing != null && existing.confidence >= confidence) {
                Log.d("AIRI_PROOF", "USER_PREF_INFER_SKIP key=$key existing_conf=${existing.confidence} new_conf=$confidence")
                return@launch
            }
            all[key] = Preference(
                key        = key,
                value      = value.take(200),
                confidence = confidence,
                updatedMs  = System.currentTimeMillis(),
                source     = "inferred",
            )
            saveAll(all)
            _preferenceCount.value = all.size
            Log.i("AIRI_PROOF", "USER_PREF_INFER key=$key value='${value.take(40)}' conf=$confidence")
        }
    }

    /** Remove a specific preference key. */
    fun removePreference(key: String) {
        scope.launch {
            val all = loadAll().toMutableMap()
            all.remove(key)
            saveAll(all)
            _preferenceCount.value = all.size
            Log.i("AIRI_PROOF", "USER_PREF_REMOVE key=$key")
        }
    }

    /** Synchronous read (SharedPreferences cache). Safe on any thread. */
    fun get(key: String): Preference? = loadAll()[key]

    /** All preferences with confidence ≥ threshold, sorted by confidence desc. */
    fun getConfident(minConfidence: Float = MIN_CONFIDENCE_TO_INJECT): List<Preference> =
        loadAll().values
            .filter { it.confidence >= minConfidence }
            .sortedByDescending { it.confidence }

    /**
     * Build a personalisation prompt block from high-confidence preferences.
     * Returns empty string if no qualifying preferences exist.
     */
    fun buildPersonalisationBlock(minConfidence: Float = MIN_CONFIDENCE_TO_INJECT): String {
        val prefs = getConfident(minConfidence)
        if (prefs.isEmpty()) return ""

        val lines = prefs.take(MAX_INJECT_PREFS).joinToString("\n") { p ->
            "- ${p.key}: ${p.value}"
        }
        return """
--- User preferences (inferred from interactions) ---
$lines
--- End preferences ---
        """.trimIndent()
    }

    fun clearAll() {
        prefs.edit().remove(KEY_PREFS).apply()
        _preferenceCount.value = 0
        Log.i("AIRI_PROOF", "USER_PREF_CLEAR_ALL")
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun loadAll(): Map<String, Preference> {
        val raw = prefs.getString(KEY_PREFS, "") ?: return emptyMap()
        if (raw.isBlank()) return emptyMap()
        return runCatching {
            raw.split(RECORD_SEP).mapNotNull { record ->
                val p = record.split(FIELD_SEP)
                if (p.size < 5) return@mapNotNull null
                val key = p[0]
                key to Preference(
                    key        = key,
                    value      = p[1],
                    confidence = p[2].toFloatOrNull() ?: 0.5f,
                    updatedMs  = p[3].toLongOrNull() ?: 0L,
                    source     = p[4],
                )
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun saveAll(map: Map<String, Preference>) {
        val encoded = map.values.joinToString(RECORD_SEP) { p ->
            listOf(
                p.key.replace(FIELD_SEP, "_").replace(RECORD_SEP, "_"),
                p.value.replace(FIELD_SEP, " ").replace(RECORD_SEP, " "),
                p.confidence.toString(),
                p.updatedMs.toString(),
                p.source.replace(FIELD_SEP, "_").replace(RECORD_SEP, "_"),
            ).joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_PREFS, encoded).apply()
    }

    companion object {
        private const val PREFS_NAME              = "airi_user_pref_memory"
        private const val KEY_PREFS               = "preferences"
        private const val RECORD_SEP              = "\u001E"
        private const val FIELD_SEP               = "\u001F"
        private const val MAX_INJECT_PREFS        = 12
        const val MIN_CONFIDENCE_TO_INJECT        = 0.45f
    }
}
