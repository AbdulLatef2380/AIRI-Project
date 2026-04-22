package com.airi.assistant.ai

import android.content.Context
import android.util.Log

/**
 * SpeculativeManager — orchestrates the optional speculative-decoding path.
 *
 *   - Persists the user's opt-in flag and the chosen draft model path in
 *     SharedPreferences ("airi_spec").
 *   - Owns the lifecycle of the draft llama_model on the native side
 *     (load / unload / reload after main-model swap).
 *   - Exposes the running acceptance rate so the Performance screen can
 *     surface whether the feature is paying for itself on this device.
 *
 * Pipeline-safety guarantees:
 *   1. If the toggle is OFF, NOTHING about the existing pipeline changes —
 *      no draft model is loaded, no per-turn mirroring happens, and the
 *      LlamaManager keeps calling the standard generateNextTokens path.
 *   2. If the toggle is ON but draft load fails (vocab mismatch, missing
 *      file, OOM, etc.), the manager logs the failure, leaves the flag ON
 *      so the user notices in the UI, but the native bridge falls back to
 *      single-token decoding for every generation. Output is unchanged.
 *   3. The chosen draft model MUST share a tokenizer with the main model
 *      (e.g. Qwen-2.5-0.5B with Qwen-2.5-3B). The native loader rejects
 *      anything with a different vocab size up-front.
 */
class SpeculativeManager(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("airi_spec", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            // Free the draft model immediately to reclaim RAM.
            runCatching { LlamaNative.unloadDraftModel() }
                .onFailure { Log.w(TAG, "unloadDraftModel: ${it.message}") }
        }
        Log.i(TAG, "AIRI_SPEC enabled=$enabled draft=${getDraftPath() ?: "—"}")
    }

    fun getDraftPath(): String? = prefs.getString(KEY_DRAFT_PATH, null)

    fun setDraftPath(path: String?) {
        prefs.edit().apply {
            if (path == null) remove(KEY_DRAFT_PATH) else putString(KEY_DRAFT_PATH, path)
        }.apply()
    }

    fun getDraftDraftN(): Int = prefs.getInt(KEY_DRAFT_N, DEFAULT_DRAFT_N)

    fun setDraftN(n: Int) {
        prefs.edit().putInt(KEY_DRAFT_N, n.coerceIn(1, 8)).apply()
    }

    /**
     * Ensure the native side has the configured draft model loaded, IF the
     * feature flag is on and a path is set. Safe to call multiple times.
     * Returns the native status string ("DRAFT_OK" or an error code) for
     * the most recent load attempt, or null if nothing was attempted.
     */
    fun ensureLoaded(): String? {
        if (!LlamaNative.isAvailable()) return null
        if (!isEnabled()) {
            if (LlamaNative.isDraftLoaded()) {
                runCatching { LlamaNative.unloadDraftModel() }
            }
            return null
        }
        val path = getDraftPath()?.takeIf { it.isNotBlank() } ?: return "NO_DRAFT_PATH"
        if (LlamaNative.isDraftLoaded()) return "DRAFT_OK"
        val status = runCatching { LlamaNative.loadDraftModel(path) }
            .getOrElse { e -> "DRAFT_EXCEPTION:${e.message}" }
        Log.i(TAG, "AIRI_SPEC draft_load path=$path status=$status")
        return status
    }

    /**
     * Auto-pick the smallest registered model that ISN'T the currently active
     * one and persist it as the draft. Returns the chosen ModelInfo or null
     * if no candidate exists.
     */
    fun autoPickDraft(candidates: List<ModelInfo>, currentMainPath: String?): ModelInfo? {
        val chosen = candidates
            .filter { it.path != currentMainPath && it.path.isNotBlank() }
            .minByOrNull { it.size }
            ?: return null
        setDraftPath(chosen.path)
        // Force a reload on the native side next ensureLoaded() call.
        runCatching { LlamaNative.unloadDraftModel() }
        Log.i(TAG, "AIRI_SPEC auto_picked path=${chosen.path} sizeMb=${chosen.size / (1024 * 1024)}")
        return chosen
    }

    fun stats(): SpecStats {
        val s = runCatching { LlamaNative.getSpecStats() }.getOrNull()
            ?: return SpecStats(0, 0, 0)
        if (s.size < 3) return SpecStats(0, 0, 0)
        return SpecStats(drafted = s[0], accepted = s[1], runs = s[2])
    }

    fun resetStats() {
        runCatching { LlamaNative.resetSpecStats() }
    }

    data class SpecStats(val drafted: Long, val accepted: Long, val runs: Long) {
        val acceptanceRate: Float get() =
            if (drafted > 0) accepted.toFloat() / drafted.toFloat() else 0f
        val acceptancePct: Int get() = (acceptanceRate * 100f).toInt()
    }

    companion object {
        private const val TAG = "SpeculativeManager"
        private const val KEY_ENABLED    = "spec_enabled"
        private const val KEY_DRAFT_PATH = "spec_draft_path"
        private const val KEY_DRAFT_N    = "spec_draft_n"
        const val DEFAULT_DRAFT_N        = 4
    }
}
