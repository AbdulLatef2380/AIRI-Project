package com.airi.assistant.agent.adaptation

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.planning.PlanAdaptationHints
import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.reflection.ReflectionReport
import com.airi.assistant.core.NodeExecutionRecord

/**
 * PlannerAdaptationEngine — the closed-loop bridge between execution outcomes
 * and future planning behavior.
 *
 * ── THE GAP THIS CLOSES ───────────────────────────────────────────────────────
 * Previously, [ExecutionReflector] produced a [ReflectionReport] after every
 * graph execution, but nothing consumed it. The planner had no memory of what
 * failed. This engine closes that loop:
 *
 *   GraphExecutionResult.reflection (ReflectionReport)
 *       ↓
 *   PlannerAdaptationEngine.ingest(report, nodeResults, goalId)
 *       ├─ PersistentLearningStore  → persists all signals across restarts
 *       ├─ StrategyEvolutionEngine  → updates recovery branch effectiveness
 *       └─ FailureIntelligenceEngine → fingerprints, cascades, quarantine
 *       ↓
 *   PlannerAdaptationEngine.applyToGenerator(planGenerator)
 *       └─ PlanGenerator.applyAdaptationHints(PlanAdaptationHints)
 *           ├─ avoidedActions   → steps of these types are removed or replaced
 *           ├─ preferSimple     → caps plan depth and disables non-root steps
 *           ├─ maxStepsHint     → hard cap on plan node count
 *           └─ recoveryBranches → per-action-type RecoveryBranch overrides
 *
 * ── PERSISTENCE ──────────────────────────────────────────────────────────────
 * All learning signals are stored in [PersistentLearningStore] via
 * SharedPreferences. They survive:
 *   - Process death
 *   - App restart
 *   - Device reboot
 *
 * ── THREAD SAFETY ────────────────────────────────────────────────────────────
 * [ingest] and [applyToGenerator] are called from coroutine contexts.
 * [PersistentLearningStore] uses SharedPreferences.apply() for async writes.
 * No locking is needed since SharedPreferences is thread-safe.
 *
 * ── SINGLETON ────────────────────────────────────────────────────────────────
 * This engine is exposed via [ServiceLocator.plannerAdaptationEngine] as a lazy
 * singleton so it accumulates learning across multiple [UnifiedCognitiveLoop]
 * instantiations within a session, AND across sessions (via the store).
 */
class PlannerAdaptationEngine(context: Context) {

    companion object {
        private const val TAG = "PlannerAdaptationEngine"
    }

    /** All persistent learning signals. Exposed for diagnostics. */
    val store           = PersistentLearningStore(context)
    val strategyEngine  = StrategyEvolutionEngine(store)
    val failureIntel    = FailureIntelligenceEngine(store)

    /**
     * Ingest a completed execution's reflection and node results.
     * Updates all learning stores and adjusts future planning hints.
     *
     * Called by [UnifiedCognitiveLoop.executeGraph] after every run.
     */
    fun ingest(
        report:      ReflectionReport,
        nodeResults: List<NodeExecutionRecord>,
        goalId:      String
    ) {
        // 1. Update overall execution confidence (EMA)
        store.recordExecutionConfidence(report.executionConfidence)

        // 2. Update per-action-type learning + strategy evolution for every node
        for (record in nodeResults) {
            store.recordActionOutcome(record.node.action, record.success)
            strategyEngine.recordNodeOutcome(
                actionType     = record.node.action,
                recoveryBranch = record.node.recoveryBranch::class.simpleName ?: "Unknown",
                attempts       = record.node.attempts,
                success        = record.success
            )
        }

        // 3. Record task-level success for this goal
        val taskSucceeded = report.executionConfidence > 0.5f
        store.recordTaskOutcome(goalId, taskSucceeded)

        // 4. Failure intelligence: fingerprints, cascades, agent trust, quarantine
        failureIntel.analyze(nodeResults)

        // 5. Dynamically adjust complexity preference from confidence trend
        val overallConfidence = store.getOverallConfidence()
        store.setPreferSimpleSteps(overallConfidence < 0.50f)

        // 6. Calibrate max-steps cap from confidence band
        //    Low confidence → force brevity → fewer nodes → less to fail
        val maxSteps = when {
            overallConfidence < 0.35f -> 2  // crisis mode: minimal plans only
            overallConfidence < 0.50f -> 3
            overallConfidence < 0.65f -> 5
            else                      -> null  // full plan complexity allowed
        }
        store.setMaxStepsHint(maxSteps)

        Log.i(TAG, "AIRI ADAPTATION_INGESTED goalId=$goalId " +
            "confidence=${"%.2f".format(report.executionConfidence)} " +
            "overall=${"%.2f".format(overallConfidence)} " +
            "preferSimple=${store.getPreferSimpleSteps()} " +
            "maxSteps=$maxSteps " +
            "avoided=${store.getAvoidedActions().size} " +
            "quarantined=${store.getQuarantinedAgents().size}")

        strategyEngine.logEvolutionState()
    }

    /**
     * Push accumulated learning hints into [generator] so the next call to
     * [PlanGenerator.createDAGPlanFromLLM] or [PlanGenerator.reduceComplexity]
     * reflects everything the system has learned.
     *
     * Called by [UnifiedCognitiveLoop] before every plan generation.
     */
    fun applyToGenerator(generator: PlanGenerator) {
        val hints = buildHints()
        generator.applyAdaptationHints(hints)

        Log.d(TAG, "HINTS_APPLIED avoided=${hints.avoidedActions.size} " +
            "simple=${hints.preferSimple} maxSteps=${hints.maxStepsHint} " +
            "branchOverrides=${hints.recoveryBranchOverrides.size} " +
            "quarantined=${hints.quarantinedAgents.size}")
    }

    /**
     * Compute the current [PlanAdaptationHints] from all accumulated learning.
     * Also used by PlanQualityScorer to check graph fragility.
     */
    fun buildHints(): PlanAdaptationHints {
        val avoidedActions = store.getAvoidedActions()

        // Build per-action-type recovery branch overrides from strategy evolution
        val recoveryOverrides = avoidedActions.associateWith { actionType ->
            strategyEngine.getBestRecoveryBranch(actionType)
        }

        return PlanAdaptationHints(
            avoidedActions         = avoidedActions,
            preferSimple           = store.getPreferSimpleSteps(),
            maxStepsHint           = store.getMaxStepsHint(),
            overallConfidence      = store.getOverallConfidence(),
            quarantinedAgents      = store.getQuarantinedAgents(),
            recoveryBranchOverrides = recoveryOverrides,
            strategyScores         = strategyEngine.getStrategyScores()
        )
    }

    /** Dump a diagnostic snapshot to logcat. */
    fun logDiagnostics() {
        store.logSnapshot()
        strategyEngine.logEvolutionState()
    }
}
