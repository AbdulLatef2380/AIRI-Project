package com.airi.assistant.ui.run

import android.content.SharedPreferences

/**
 * Part 5 §3.8: the optional reason behind a thumbs-down. It is stored on the device only (it is
 * never sent anywhere) and is removed with the run it belongs to.
 */
enum class RatingReason { INACCURATE, INCOMPLETE, SLOW, OTHER }

/** Reasons are asked for only after a thumbs-down, never after a thumbs-up or no vote. */
fun ratingReasonsFor(feedback: Int): List<RatingReason> = if (feedback < 0) RatingReason.entries else emptyList()

/** Pure text codec so the storage rules are unit-testable without Android. */
object RunFeedbackCodec {
    const val MAX_ENTRIES = 200
    private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")

    fun isStorable(runId: String): Boolean = SAFE_ID.matches(runId)

    /** One `runId|REASON` per line; unknown reasons and malformed lines are dropped. */
    fun decode(raw: String?): LinkedHashMap<String, RatingReason> {
        val out = LinkedHashMap<String, RatingReason>()
        raw.orEmpty().lineSequence().forEach { line ->
            val cut = line.indexOf('|')
            if (cut <= 0) return@forEach
            val id = line.substring(0, cut)
            val reason = RatingReason.entries.firstOrNull { it.name == line.substring(cut + 1) } ?: return@forEach
            if (isStorable(id)) out[id] = reason
        }
        return out
    }

    fun encode(map: Map<String, RatingReason>): String = map.entries.joinToString("\n") { "${it.key}|${it.value.name}" }

    /** Sets or (with null) clears the reason of one run; the oldest entries fall out past [MAX_ENTRIES]. */
    fun update(map: LinkedHashMap<String, RatingReason>, runId: String, reason: RatingReason?): LinkedHashMap<String, RatingReason> {
        if (!isStorable(runId)) return map
        map.remove(runId)
        if (reason != null) map[runId] = reason
        while (map.size > MAX_ENTRIES) map.remove(map.keys.first())
        return map
    }
}

class RunFeedbackStore(private val prefs: SharedPreferences) {
    private fun load() = RunFeedbackCodec.decode(prefs.getString(KEY, null))

    fun reasonFor(runId: String): RatingReason? = load()[runId]

    fun setReason(runId: String, reason: RatingReason?) = write(RunFeedbackCodec.update(load(), runId, reason))

    fun remove(runId: String) = setReason(runId, null)

    fun clear() { prefs.edit().remove(KEY).apply() }

    private fun write(map: Map<String, RatingReason>) { prefs.edit().putString(KEY, RunFeedbackCodec.encode(map)).apply() }

    companion object {
        const val PREFS_NAME = "agent_run_feedback"
        private const val KEY = "reasons"

        fun from(context: android.content.Context) =
            RunFeedbackStore(context.applicationContext.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE))
    }
}
