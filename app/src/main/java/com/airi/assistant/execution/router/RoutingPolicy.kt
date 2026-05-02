package com.airi.assistant.execution.router

import com.airi.assistant.ai.QueryType
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.backend.CloudBackend
import com.airi.assistant.execution.backend.LocalLlamaBackend
import com.airi.assistant.execution.backend.RuntimeBackend
import com.airi.assistant.execution.prefs.ExecModePreferences

/**
 * Pure-function routing rule engine.
 *
 * [select] is a deterministic, side-effect-free function that maps a
 * (request, device signals, preferences) triple to an ordered list of
 * backend candidates. The first entry is the primary backend; remaining
 * entries are fallbacks tried in order if the primary fails.
 *
 * The function never performs I/O. It reads only the parameters passed in
 * so it is safe to call from any thread, including unit tests.
 *
 * ## Rule priority (evaluated top to bottom, first match wins for primary):
 *
 *  1. ExecutionMode.LOCAL_ONLY          → always [local], no fallback to cloud
 *  2. PrivacyLevel.MAXIMUM              → same as LOCAL_ONLY, always [local]
 *  3. No internet permission            → [local] only
 *  4. ExecutionMode.CLOUD_ONLY + online → [cloud], fallback = [local] if
 *                                         offlineFallback is enabled
 *  5. Capability mismatch (local can't  → [cloud], fallback = [local]
 *     satisfy request requirements)
 *  6. Device under severe thermal/RAM   → [cloud], fallback = [local]
 *     pressure AND cloud is available
 *  7. Deep reasoning / large context    → [cloud] preferred in HYBRID
 *     query (ANALYTICAL, long prompt)
 *  8. Privacy / local task              → [local] preferred in HYBRID
 *     (ACTION, SIMPLE, offline, a11y)
 *  9. Cloud budget exhausted            → [local]
 * 10. Default HYBRID                    → [local], fallback = [cloud]
 *
 * Rationale for rule 10 default: local inference preserves privacy and
 * avoids network latency. Cloud is the fallback, not the primary.
 */
object RoutingPolicy {

    /**
     * Selection result — ordered list of backends to attempt.
     * At least one backend will always be in the list.
     *
     * @param backends  Ordered candidates, primary first.
     * @param rationale Short human-readable explanation (for event log).
     */
    data class Selection(
        val backends:  List<RuntimeBackend>,
        val rationale: String
    ) {
        val primary:  RuntimeBackend  get() = backends.first()
        val fallback: RuntimeBackend? get() = backends.getOrNull(1)
    }

    fun select(
        request:  ExecutionRequest,
        signals:  DeviceSignals,
        prefs:    ExecModePreferences,
        local:    LocalLlamaBackend,
        cloud:    CloudBackend
    ): Selection {

        val mode    = prefs.effectiveMode    // already applies privacy + perm gates
        val privacy = prefs.privacyLevel
        val fallbackEnabled = prefs.offlineFallbackEnabled

        // ── Rule 1 + 2 + 3: hard local gates ─────────────────────────────────
        if (mode == ExecutionMode.LOCAL_ONLY ||
            privacy == PrivacyLevel.MAXIMUM ||
            !prefs.internetPermissionGranted) {
            return Selection(listOf(local), "LOCAL_ONLY / privacy=MAXIMUM / no internet permission")
        }

        // ── Rule 4: CLOUD_ONLY ─────────────────────────────────────────────────
        if (mode == ExecutionMode.CLOUD_ONLY) {
            return if (signals.networkAvailable && cloud.isAvailable) {
                val fallbacks = if (fallbackEnabled && local.isAvailable) listOf(cloud, local) else listOf(cloud)
                Selection(fallbacks, "CLOUD_ONLY mode — online")
            } else if (fallbackEnabled && local.isAvailable) {
                Selection(listOf(local), "CLOUD_ONLY mode — offline, falling back to local")
            } else {
                Selection(listOf(cloud), "CLOUD_ONLY mode — offline, no fallback")
            }
        }

        // ── Rule 5: capability mismatch ────────────────────────────────────────
        val localCaps = local.capabilities
        if (!localCaps.satisfies(request)) {
            if (signals.networkAvailable && cloud.isAvailable) {
                val fallbacks = if (local.isAvailable) listOf(cloud, local) else listOf(cloud)
                return Selection(fallbacks,
                    "Local cannot satisfy: ${request.requirementSummary}")
            }
            // Cloud also unavailable — proceed with local anyway (best-effort)
            return Selection(listOf(local),
                "Local cap mismatch but cloud unavailable; local best-effort")
        }

        // ── Rule 6: device stress → cloud offload ──────────────────────────────
        if (signals.preferCloudForPerformance &&
            signals.networkAvailable && cloud.isAvailable) {
            val fallbacks = if (local.isAvailable) listOf(cloud, local) else listOf(cloud)
            return Selection(fallbacks,
                "Device stress (thermal=${signals.thermalLevel} ram=${signals.availRamMb}MB) → cloud offload")
        }

        // ── Rules 7 + 8: HYBRID query-type routing ────────────────────────────
        if (mode == ExecutionMode.HYBRID && signals.networkAvailable && cloud.isAvailable) {
            val cloudPreferred = isCloudPreferred(request, signals, prefs)
            if (cloudPreferred) {
                val fallbacks = if (local.isAvailable) listOf(cloud, local) else listOf(cloud)
                return Selection(fallbacks,
                    "HYBRID: cloud preferred for ${request.queryType.name} / long=${request.requiresLongContext}")
            }
        }

        // ── Rule 9: cloud budget exhausted ────────────────────────────────────
        if (prefs.isCloudBudgetExhausted) {
            return Selection(listOf(local), "Cloud daily budget exhausted")
        }

        // ── Rule 10: default HYBRID → local primary, cloud fallback ──────────
        val fallbacks = if (mode == ExecutionMode.HYBRID &&
            signals.networkAvailable && cloud.isAvailable)
            listOf(local, cloud) else listOf(local)
        return Selection(fallbacks, "HYBRID default: local primary, cloud fallback")
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns true when the request characteristics suggest cloud will
     * produce a meaningfully better result than local inference.
     *
     * Heuristics (not exhaustive):
     *  - ANALYTICAL or CREATIVE query type (deep reasoning)
     *  - Long context required
     *  - Prompt is large (> 600 chars ≈ > 150 tokens, suggesting summarisation)
     *  - Speculative research or planning keywords detected
     */
    private fun isCloudPreferred(
        request: ExecutionRequest,
        signals: DeviceSignals,
        prefs:   ExecModePreferences
    ): Boolean {
        if (request.requiresLongContext)     return true
        if (request.requiresVision &&
            !request.requiresOffline)        return true

        val isDeepQuery = request.queryType == QueryType.ANALYTICAL ||
                          request.queryType == QueryType.CREATIVE
        if (isDeepQuery && request.estimatedPromptTokens > 200) return true

        // Large prompt → likely summarisation or document analysis
        if (request.prompt.length > 1200)    return true

        // Device can't run local well right now even if not in stress zone
        if (!signals.isLocalCapable)         return true

        return false
    }
}
