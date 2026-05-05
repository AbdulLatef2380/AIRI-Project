package com.airi.assistant.memory

import android.content.Context
import android.util.Log
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EpisodicMemoryStore — structured long-term episodic memory.
 *
 * Episodic memory stores *events* that the agent explicitly decides are worth
 * remembering — completed tasks, user goals, important facts — as opposed to
 * the raw rolling conversation window managed by [MemoryManager].
 *
 * ## Architecture
 * Episodes are serialized as JSON in SharedPreferences. This is intentional:
 *   - Room already holds the raw message log (chat history).
 *   - Episodic episodes are a much smaller set of curated high-level events.
 *   - SharedPreferences gives instant synchronous reads for prompt injection.
 *
 * ## Lifecycle
 * 1. The UCL / orchestrator calls [recordEpisode] when a task goal completes.
 * 2. [buildEpisodicContext] is called before each LLM turn to inject relevant
 *    episodes as a system-prompt block.
 * 3. Episodes older than [RETENTION_DAYS] are pruned on each app start.
 *
 * ## Threading
 * All writes are fire-and-forget on [Dispatchers.IO]. Reads are synchronous
 * (SharedPreferences in-memory cache — safe on any thread).
 */
class EpisodicMemoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _episodeCount = MutableStateFlow(0)
    val episodeCount: StateFlow<Int> = _episodeCount.asStateFlow()

    init {
        pruneOldEpisodes()
        _episodeCount.value = loadAll().size
    }

    // ── Public API ────────────────────────────────────────────────────────────

    data class Episode(
        val id:          String,
        val timestampMs: Long,
        val category:    String,
        val summary:     String,
        val tags:        List<String> = emptyList(),
    )

    /**
     * Record a new episodic event. Fire-and-forget.
     *
     * @param category  Broad category: "task_completed", "user_goal", "fact_learned",
     *                  "error_corrected", "preference_inferred"
     * @param summary   One-sentence human-readable summary of what happened.
     * @param tags      Optional string tags for search/filtering.
     */
    fun recordEpisode(category: String, summary: String, tags: List<String> = emptyList()) {
        scope.launch {
            val id = "ep_${System.currentTimeMillis()}_${(0..999).random()}"
            val ep = Episode(
                id          = id,
                timestampMs = System.currentTimeMillis(),
                category    = category,
                summary     = summary.take(MAX_SUMMARY_CHARS),
                tags        = tags.take(10),
            )
            val all = loadAll().toMutableList()
            all.add(ep)
            if (all.size > MAX_EPISODES) {
                all.sortByDescending { it.timestampMs }
                all.subList(MAX_EPISODES, all.size).clear()
            }
            saveAll(all)
            _episodeCount.value = all.size
            Log.i("AIRI_PROOF", "EPISODIC_RECORD id=$id category=$category tags=${tags.take(3)}")
        }
    }

    /**
     * Build a system-prompt context block from recent relevant episodes.
     * Returns an empty string if no episodes exist.
     */
    fun buildEpisodicContext(maxEpisodes: Int = 8): String {
        val episodes = loadAll()
            .sortedByDescending { it.timestampMs }
            .take(maxEpisodes)
        if (episodes.isEmpty()) return ""

        val formatted = episodes.joinToString("\n") { ep ->
            "[${ep.category.uppercase()}] ${ep.summary}"
        }
        return """
--- Episodic memory (past events) ---
$formatted
--- End episodic memory ---
        """.trimIndent()
    }

    /** All stored episodes, newest first. */
    fun getAllEpisodes(): List<Episode> =
        loadAll().sortedByDescending { it.timestampMs }

    /** Delete all episodes (e.g. on memory reset). */
    fun clearAll() {
        prefs.edit().remove(KEY_EPISODES).apply()
        _episodeCount.value = 0
        Log.i("AIRI_PROOF", "EPISODIC_CLEAR_ALL")
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun loadAll(): List<Episode> {
        val raw = prefs.getString(KEY_EPISODES, "") ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            raw.split(RECORD_SEP).mapNotNull { record ->
                val parts = record.split(FIELD_SEP)
                if (parts.size < 4) return@mapNotNull null
                Episode(
                    id          = parts[0],
                    timestampMs = parts[1].toLongOrNull() ?: return@mapNotNull null,
                    category    = parts[2],
                    summary     = parts[3],
                    tags        = if (parts.size > 4) parts[4].split(",").filter { it.isNotBlank() } else emptyList(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveAll(episodes: List<Episode>) {
        val encoded = episodes.joinToString(RECORD_SEP) { ep ->
            listOf(
                ep.id,
                ep.timestampMs.toString(),
                ep.category.replace(FIELD_SEP, "_").replace(RECORD_SEP, "_"),
                ep.summary.replace(FIELD_SEP, " ").replace(RECORD_SEP, " "),
                ep.tags.joinToString(","),
            ).joinToString(FIELD_SEP)
        }
        prefs.edit().putString(KEY_EPISODES, encoded).apply()
    }

    private fun pruneOldEpisodes() {
        val cutoff = System.currentTimeMillis() - RETENTION_DAYS * 24 * 60 * 60 * 1000L
        val all    = loadAll()
        val pruned = all.filter { it.timestampMs >= cutoff }
        if (pruned.size < all.size) {
            saveAll(pruned)
            Log.i("AIRI_PROOF", "EPISODIC_PRUNE removed=${all.size - pruned.size} retained=${pruned.size}")
        }
    }

    companion object {
        private const val PREFS_NAME       = "airi_episodic_memory"
        private const val KEY_EPISODES     = "episodes"
        private const val RECORD_SEP       = "\u001E"
        private const val FIELD_SEP        = "\u001F"
        private const val MAX_EPISODES     = 200
        private const val MAX_SUMMARY_CHARS = 300
        private const val RETENTION_DAYS   = 90L
    }
}
