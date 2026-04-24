package com.airi.assistant.domain.experiment

import android.content.Context
import android.content.SharedPreferences
import com.airi.assistant.domain.logging.LoggingService
import kotlin.math.abs

/**
 * Lightweight A/B experiment manager.
 * - Assigns users deterministically to variants based on userId hash.
 * - Persists assignments in SharedPreferences so they are stable across sessions.
 * - Variants are read by UI components for copy/UX changes.
 */
object ExperimentManager {

    private const val TAG        = "ExperimentManager"
    private const val PREFS_NAME = "airi_experiments"

    enum class Variant { A, B }

    data class Experiment(
        val key:         String,
        val description: String,
        val variantA:    String,
        val variantB:    String
    )

    // ── Defined experiments ───────────────────────────────────────────────────

    val PAYWALL_HEADLINE = Experiment(
        key         = "paywall_headline_v1",
        description = "Paywall headline copy test",
        variantA    = "Unlock Full AI Power",
        variantB    = "Limited Time: Upgrade Now"
    )

    val PAYWALL_CTA = Experiment(
        key         = "paywall_cta_v1",
        description = "CTA button label test",
        variantA    = "Unlock AIRI Premium",
        variantB    = "Start Premium — $4.99/mo"
    )

    val PAYWALL_URGENCY = Experiment(
        key         = "paywall_urgency_v1",
        description = "Show urgency strip under CTA",
        variantA    = "false",
        variantB    = "true"
    )

    private val allExperiments = listOf(PAYWALL_HEADLINE, PAYWALL_CTA, PAYWALL_URGENCY)

    // ── State ─────────────────────────────────────────────────────────────────

    private var prefs: SharedPreferences? = null
    private var userId: String = "anonymous"

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context, userId: String = "anonymous") {
        this.userId  = userId
        this.prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        assignAll()
    }

    private fun assignAll() {
        val p = prefs ?: return
        allExperiments.forEach { exp ->
            if (!p.contains(exp.key)) {
                val hash    = (userId + exp.key).hashCode()
                val variant = if (abs(hash) % 2 == 0) Variant.A.name else Variant.B.name
                p.edit().putString(exp.key, variant).apply()
                LoggingService.debug(TAG, "Assigned variant $variant → ${exp.key}")
            }
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    fun getVariant(experiment: Experiment): Variant {
        val saved = prefs?.getString(experiment.key, Variant.A.name) ?: Variant.A.name
        return runCatching { Variant.valueOf(saved) }.getOrDefault(Variant.A)
    }

    fun getValue(experiment: Experiment): String =
        if (getVariant(experiment) == Variant.A) experiment.variantA else experiment.variantB

    fun getBool(experiment: Experiment): Boolean =
        getValue(experiment).equals("true", ignoreCase = true)

    // ── Override (for QA / testing) ───────────────────────────────────────────

    fun forceVariant(experiment: Experiment, variant: Variant) {
        prefs?.edit()?.putString(experiment.key, variant.name)?.apply()
        LoggingService.info(TAG, "Force override: ${experiment.key} → $variant")
    }

    fun getAllAssignments(): Map<String, String> =
        allExperiments.associate { it.key to getValue(it) }
}
