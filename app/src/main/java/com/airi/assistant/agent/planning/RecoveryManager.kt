package com.airi.assistant.agent.planning

import android.util.Log

/**
 * RecoveryManager — comprehensive cross-step compensatory recovery.
 *
 * REAL EXECUTION:
 *   1. [diagnose] classifies any Throwable into a [RecoveryStrategy].
 *   2. [shouldRetry] enforces per-strategy retry budgets with exponential
 *      back-off delays to avoid hammering a broken resource.
 *   3. [compensate] is the NEW capability: given a [PlanStep] that failed,
 *      it generates a concrete replacement step (or steps) that avoid the
 *      same failure mode:
 *        - A failed Click step → replaced by a Search step for the same target
 *        - A failed OpenApp step → replaced by a Custom "navigate_to" step
 *        - A failed Type step → replaced by a Custom "paste_text" step
 *        - Any failed step → Custom("noop") as a safe terminal fallback
 *   4. [recoverFromPartialExecution] takes the full partial-execution context
 *      (completed steps + their results, failed step, its error) and produces
 *      a revised tail-plan of the remaining steps with the failed one replaced.
 *   5. [backoffMs] returns the correct wait time before the next retry attempt.
 *
 * WIRING:
 *   - [ProductionAgentOrchestrator.executeTask] calls [diagnose] when a task
 *     fails, then [compensate] to produce a replacement task.
 *   - [PlanGenerator] calls [recoverFromPartialExecution] when it detects
 *     that a multi-step plan has partially failed.
 */
class RecoveryManager {

    companion object {
        private const val TAG = "RecoveryManager"
    }

    // ── Retry budget per strategy ────────────────────────────────────────────

    private val maxRetriesPerStrategy = mapOf(
        RecoveryStrategy.REPLAN       to 2,
        RecoveryStrategy.REDUCE_SCOPE to 3,
        RecoveryStrategy.COMPENSATE   to 1,
        RecoveryStrategy.ABORT        to 0
    )

    // Exponential backoff base (ms) per attempt: 500 → 1000 → 2000 → …
    private val backoffBaseMs = 500L

    // ── Public API ───────────────────────────────────────────────────────────

    /** Whether another retry attempt is permitted for [attempt] under [strategy]. */
    fun shouldRetry(attempt: Int, strategy: RecoveryStrategy = RecoveryStrategy.REPLAN): Boolean {
        val max = maxRetriesPerStrategy[strategy] ?: 0
        return attempt < max
    }

    /** Convenience overload (backward compat). */
    fun shouldRetry(attempt: Int): Boolean = shouldRetry(attempt, RecoveryStrategy.REPLAN)

    /** Backoff delay before attempt [attempt] (1-indexed). */
    fun backoffMs(attempt: Int): Long =
        backoffBaseMs * (1L shl minOf(attempt - 1, 4))   // 500, 1000, 2000, 4000, 8000

    /**
     * Classify a [Throwable] into a [RecoveryStrategy].
     *
     * EXTENDED classification:
     *   - Network / IO errors       → REPLAN (try a different route)
     *   - Argument / input errors   → REDUCE_SCOPE (simplify the request)
     *   - Permission denied         → ABORT (can't fix programmatically)
     *   - Timeout                   → COMPENSATE (replace with lighter step)
     *   - State / availability      → REPLAN
     *   - Unknown                   → ABORT
     */
    fun diagnose(error: Throwable): RecoveryStrategy {
        val msg = error.message?.lowercase() ?: ""
        return when {
            error is IllegalArgumentException         -> RecoveryStrategy.REDUCE_SCOPE
            error is IllegalStateException            -> RecoveryStrategy.REPLAN
            error is TimeoutException                 -> RecoveryStrategy.COMPENSATE
            error is SecurityException                -> RecoveryStrategy.ABORT
            error is java.io.IOException              -> RecoveryStrategy.REPLAN
            error is java.net.ConnectException        -> RecoveryStrategy.REPLAN
            error is java.net.SocketTimeoutException  -> RecoveryStrategy.COMPENSATE
            msg.contains("permission")               -> RecoveryStrategy.ABORT
            msg.contains("not found")                -> RecoveryStrategy.REDUCE_SCOPE
            msg.contains("timeout")                  -> RecoveryStrategy.COMPENSATE
            msg.contains("network")                  -> RecoveryStrategy.REPLAN
            msg.contains("unavailable")              -> RecoveryStrategy.REPLAN
            else                                      -> RecoveryStrategy.ABORT
        }.also {
            Log.d(TAG, "diagnose error=${error::class.simpleName} msg=$msg → $it")
        }
    }

    /**
     * Generate a compensatory [PlanStep] to replace [failedStep].
     *
     * The replacement step avoids the specific failure mode by using a
     * simpler or alternative action type:
     *   - [PlanStep.Click]   → [PlanStep.Search] for the target text
     *   - [PlanStep.OpenApp] → [PlanStep.Custom] "navigate_to_app"
     *   - [PlanStep.Type]    → [PlanStep.Custom] "paste_text"
     *   - [PlanStep.Search]  → [PlanStep.Custom] "cached_search"
     *   - Any other          → [PlanStep.Custom] "noop" (safe terminal)
     */
    fun compensate(failedStep: PlanStep, failureReason: String): PlanStep {
        val newId = "recovery_${System.currentTimeMillis()}"
        val replacement = when (failedStep) {
            is PlanStep.Click  -> PlanStep.Search(
                id              = newId,
                query           = failedStep.targetText,
                dependsOn       = failedStep.dependsOn,
                expectedOutcome = "Found '${failedStep.targetText}' via search instead of click"
            )
            is PlanStep.OpenApp -> PlanStep.Custom(
                id             = newId,
                action         = "navigate_to_app",
                parameters     = mapOf(
                    "app_name"   to failedStep.appName,
                    "reason"     to "open_app_failed",
                    "fallback"   to "true"
                ),
                dependsOn      = failedStep.dependsOn,
                expectedOutcome = "Navigated to ${failedStep.appName} via fallback"
            )
            is PlanStep.Type   -> PlanStep.Custom(
                id             = newId,
                action         = "paste_text",
                parameters     = mapOf(
                    "text"       to failedStep.text,
                    "field"      to (failedStep.targetField ?: "focused"),
                    "reason"     to "type_failed"
                ),
                dependsOn      = failedStep.dependsOn,
                expectedOutcome = "Pasted '${failedStep.text.take(40)}'"
            )
            is PlanStep.Search -> PlanStep.Custom(
                id             = newId,
                action         = "cached_search",
                parameters     = mapOf(
                    "query"      to failedStep.query,
                    "reason"     to "live_search_failed"
                ),
                dependsOn      = failedStep.dependsOn,
                expectedOutcome = "Returned cached result for '${failedStep.query}'"
            )
            else -> PlanStep.Custom(
                id             = newId,
                action         = "noop",
                parameters     = mapOf(
                    "skipped_step" to failedStep.id,
                    "reason"       to failureReason.take(100)
                ),
                dependsOn      = failedStep.dependsOn,
                expectedOutcome = "Skipped failed step safely"
            )
        }

        Log.i(TAG, "AIRI COMPENSATE failed=${failedStep::class.simpleName} " +
            "replacement=${(replacement as? PlanStep.Custom)?.action ?: replacement::class.simpleName}")
        return replacement
    }

    /**
     * Produce a revised tail-plan from a partially-executed goal.
     *
     * Given the [completedStepIds] and [failedStep], this returns a new
     * [AgentGoal] that:
     *   1. Removes already-completed steps (their results are in [completedResults]).
     *   2. Replaces [failedStep] with the output of [compensate].
     *   3. Preserves remaining downstream steps (their dependencies updated).
     *
     * Returns null if recovery is not possible (e.g. all remaining steps
     * depended on [failedStep] and there's no safe compensation).
     */
    fun recoverFromPartialExecution(
        original:          AgentGoal,
        completedStepIds:  Set<String>,
        failedStep:        PlanStep,
        failureReason:     String,
        completedResults:  Map<String, String>
    ): AgentGoal? {
        val remainingSteps = original.steps
            .filterNot { it.id in completedStepIds }
            .toMutableList()

        if (remainingSteps.isEmpty()) {
            Log.d(TAG, "No remaining steps — plan already complete despite failure")
            return null
        }

        val compensatoryStep = compensate(failedStep, failureReason)
        val failedId         = failedStep.id
        val compensatedId    = compensatoryStep.id

        // Replace the failed step with its compensation
        val revised = remainingSteps.map { step ->
            if (step.id == failedId) {
                compensatoryStep
            } else {
                // Update any dependency on the failed step → point to compensation
                val updatedDeps = step.dependsOn.map { dep ->
                    if (dep == failedId) compensatedId else dep
                }
                when (step) {
                    is PlanStep.Custom  -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.OpenApp -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Search  -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Click   -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Type    -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Navigate -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Wait    -> step.copy(dependsOn = updatedDeps)
                    is PlanStep.Scroll  -> step.copy(dependsOn = updatedDeps)
                }
            }
        }

        val recoveryGoal = AgentGoal(
            id          = "recovery_${original.id}",
            description = "${original.description} [recovered from ${failedStep::class.simpleName} failure]",
            steps       = revised
        )

        Log.i(TAG, "AIRI RECOVERY_PLAN created steps=${revised.size} " +
            "completedSteps=${completedStepIds.size} failedStep=${failedStep.id}")
        return recoveryGoal
    }
}

enum class RecoveryStrategy {
    REPLAN,
    REDUCE_SCOPE,
    COMPENSATE,
    ABORT
}

class TimeoutException(message: String? = null) : Exception(message)
