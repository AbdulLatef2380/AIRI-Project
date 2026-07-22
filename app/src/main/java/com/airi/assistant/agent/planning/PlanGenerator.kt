package com.airi.assistant.agent.planning

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

class PlanGenerator {

    companion object {
        private const val TAG = "PlanGenerator"
    }

    fun createActionPlanFromLLM(llmResponse: String, fallbackDescription: String = "Unknown goal"): ActionPlan {
        val agentGoal = createPlanFromLLM(llmResponse, fallbackDescription)
        return toActionPlan(agentGoal)
    }

    fun createPlanFromLLM(llmResponse: String, fallbackDescription: String = "Unknown goal"): AgentGoal {
        return try {
            val jsonStr = extractJSON(llmResponse)
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                parsePlanFromJSON(json, fallbackDescription)
            } else {
                Log.w(TAG, "No JSON found in LLM response, creating fallback plan")
                createFallbackPlan(fallbackDescription)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse LLM plan: ${e.message}", e)
            createFallbackPlan(fallbackDescription)
        }
    }

    fun toActionPlan(goal: AgentGoal): ActionPlan {
        return ActionPlan(
            intent = goal.description,
            confidence = 0.85,
            steps = goal.steps,
            requiresConfirmation = false
        )
    }

    fun createPlan(input: BrainInput): AgentGoal {
        return AgentGoal(
            id = "goal_${System.currentTimeMillis()}",
            description = input.text,
            steps = listOf(
                PlanStep.Custom(
                    id = "1",
                    action = "process_input",
                    parameters = mapOf("text" to input.text, "context" to input.screenContext),
                    dependsOn = emptyList(),
                    expectedOutcome = "Input processed"
                )
            )
        )
    }

    fun createDAGPlanFromLLM(llmResponse: String, fallbackDescription: String = "Unknown goal"): ActionPlan {
        val goal       = createPlanFromLLM(llmResponse, fallbackDescription)
        val normalized = normalizeGoal(goal)
        // Apply accumulated learning: strip avoided action types, collapse
        // Wait sequences, enforce max-step caps. No-op when no hints are set.
        val adapted    = reduceComplexity(normalized)
        return ActionPlan(
            intent               = adapted.description,
            confidence           = scoreGoalConfidence(adapted),
            steps                = adapted.steps,
            requiresConfirmation = adapted.steps.size > 3
        )
    }

    private fun parsePlanFromJSON(json: JSONObject, fallbackDescription: String): AgentGoal {
        val goalDescription = json.optString("goal", fallbackDescription)
        val stepsArray = json.optJSONArray("steps") ?: JSONArray()

        val steps = mutableListOf<PlanStep>()
        for (i in 0 until stepsArray.length()) {
            val stepJson = stepsArray.getJSONObject(i)
            val step = parseStep(stepJson)
            if (step != null) {
                steps.add(step)
            }
        }

        if (!validateDependencies(steps)) {
            Log.w(TAG, "Plan has invalid dependencies, using fallback")
            return createFallbackPlan(goalDescription)
        }

        return AgentGoal(
            id = "goal_${System.currentTimeMillis()}",
            description = goalDescription,
            steps = steps
        )
    }

    private fun parseStep(stepJson: JSONObject): PlanStep? {
        return try {
            val id = stepJson.optString("id", "step_${System.currentTimeMillis()}")
            val action = stepJson.optString("action", "").lowercase()
            val params = stepJson.optJSONObject("params") ?: JSONObject()
            val dependsOn = parseDependencies(stepJson.optJSONArray("depends_on"))
            val expected = stepJson.optString("expected").takeIf { it.isNotEmpty() }

            when (action) {
                "open_app" -> PlanStep.OpenApp(
                    id = id,
                    // BUG FIX: ACTION_PLAN_SUFFIX template uses "app" as the params key.
                    // The old code only checked "app_name" and "name", so every LLM-generated
                    // open_app step resolved to appName="unknown" and launchApp would always
                    // fail with "App not found: unknown". Check "app" first (most common from
                    // our prompt template), then "app_name", then "name", then "package".
                    appName = params.optString("app",
                                params.optString("app_name",
                                params.optString("name",
                                params.optString("package", "unknown")))),
                    packageName = params.optString("package").takeIf { it.isNotEmpty() },
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "search", "search_web" -> PlanStep.Search(
                    id = id,
                    // BUG FIX: ACTION_PLAN_SUFFIX template emits "target" for search queries
                    // (same field name as click targets). The old code only checked "query",
                    // which is always empty in LLM-generated plans → SearchQuery was silently "".
                    // Check in priority order: "query" (explicit), "target" (template default),
                    // "q" (shorthand), "text" (generic input).
                    query = params.optString("query",
                              params.optString("target",
                              params.optString("q",
                              params.optString("text", "")))),
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "click", "tap" -> PlanStep.Click(
                    id = id,
                    targetText = params.optString("target", params.optString("text", "")),
                    targetId = params.optString("id").takeIf { it.isNotEmpty() },
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "type", "input" -> PlanStep.Type(
                    id = id,
                    text = params.optString("text", ""),
                    targetField = params.optString("field").takeIf { it.isNotEmpty() },
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "navigate", "nav" -> {
                    val direction = when (params.optString("direction", "back").lowercase()) {
                        "home" -> PlanStep.Navigate.NavigationDirection.HOME
                        "recents", "recent" -> PlanStep.Navigate.NavigationDirection.RECENTS
                        else -> PlanStep.Navigate.NavigationDirection.BACK
                    }
                    PlanStep.Navigate(
                        id = id,
                        direction = direction,
                        dependsOn = dependsOn,
                        expectedOutcome = expected
                    )
                }

                "wait", "delay" -> PlanStep.Wait(
                    id = id,
                    durationMs = params.optLong("duration_ms", params.optLong("ms", 1000)),
                    condition = params.optString("condition").takeIf { it.isNotEmpty() },
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "scroll" -> {
                    val direction = when (params.optString("direction", "down").lowercase()) {
                        "up" -> PlanStep.Scroll.ScrollDirection.UP
                        "left" -> PlanStep.Scroll.ScrollDirection.LEFT
                        "right" -> PlanStep.Scroll.ScrollDirection.RIGHT
                        else -> PlanStep.Scroll.ScrollDirection.DOWN
                    }
                    PlanStep.Scroll(
                        id = id,
                        direction = direction,
                        amount = params.optInt("amount", 1),
                        dependsOn = dependsOn,
                        expectedOutcome = expected
                    )
                }

                
                // Previously these fell through to PlanStep.Custom with action="synthesize"
                // (or "respond"/"conversation") and were silently acknowledged by
                // CommandRouter Tier 3. Now they are first-class plan steps so that:
                //   - "synthesize" → PlanStep.Custom("synthesize") with the response text
                //     in params["text"], picked up by CommandRouter Tier 1 and returned
                //     as a real CommandResult.message.
                //   - "respond" / "conversation" → same path, used when the LLM decides
                //     the request is conversational rather than action-oriented.
                "synthesize", "respond", "response" -> PlanStep.Custom(
                    id = id,
                    action = "synthesize",
                    parameters = jsonObjectToMap(params).let { m ->
                        // Normalise: ensure the response text is always under "text" key
                        val textVal = m["text"] ?: m["content"] ?: m["message"] ?: m["response"] ?: ""
                        if (textVal.isNotBlank()) m + mapOf("text" to textVal) else m
                    },
                    dependsOn = dependsOn,
                    expectedOutcome = expected ?: "Conversational response"
                )

                "conversation", "converse", "reply", "answer" -> PlanStep.Custom(
                    id = id,
                    action = "conversation",
                    parameters = jsonObjectToMap(params).let { m ->
                        val textVal = m["text"] ?: m["content"] ?: m["message"] ?: ""
                        if (textVal.isNotBlank()) m + mapOf("text" to textVal) else m
                    },
                    dependsOn = dependsOn,
                    expectedOutcome = expected ?: "Conversational reply"
                )

                else -> PlanStep.Custom(
                    id = id,
                    action = action,
                    parameters = jsonObjectToMap(params),
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse step: ${e.message}")
            null
        }
    }

    private fun extractJSON(text: String): String? {
        val jsonStartIdx = text.indexOf('{')
        val jsonEndIdx = text.lastIndexOf('}')
        return if (jsonStartIdx >= 0 && jsonEndIdx > jsonStartIdx) {
            text.substring(jsonStartIdx, jsonEndIdx + 1)
        } else null
    }

    private fun parseDependencies(jsonArray: JSONArray?): List<String> {
        if (jsonArray == null) return emptyList()
        val deps = mutableListOf<String>()
        for (i in 0 until jsonArray.length()) {
            deps.add(jsonArray.optString(i))
        }
        return deps
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        json.keys().forEach { key -> map[key] = json.optString(key, "") }
        return map
    }

    private fun validateDependencies(steps: List<PlanStep>): Boolean {
        val stepIds = steps.map { it.id }.toSet()
        for (step in steps) {
            for (depId in step.dependsOn) {
                if (depId !in stepIds) {
                    Log.w(TAG, "Step ${step.id} depends on non-existent step: $depId")
                    return false
                }
            }
        }
        return true
    }

    private fun createFallbackPlan(description: String): AgentGoal {
        return AgentGoal(
            id = "goal_${System.currentTimeMillis()}",
            description = description,
            steps = listOf(
                PlanStep.Custom(
                    id = "fallback_1",
                    action = "conversation",
                    parameters = mapOf("reason" to "plan_parsing_failed"),
                    dependsOn = emptyList(),
                    expectedOutcome = "Conversational response"
                )
            )
        )
    }

    private fun normalizeGoal(goal: AgentGoal): AgentGoal {
        val steps = goal.steps.mapIndexed { index, step ->
            if (step.dependsOn.isEmpty() && index > 0) {
                when (step) {
                    is PlanStep.Custom -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.OpenApp -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Search -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Click -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Type -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Navigate -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Wait -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                    is PlanStep.Scroll -> step.copy(dependsOn = listOf(goal.steps[index - 1].id))
                }
            } else {
                step
            }
        }
        return goal.copy(steps = steps)
    }

    private fun scoreGoalConfidence(goal: AgentGoal): Double {
        val total = goal.steps.size.coerceAtLeast(1)
        val chained = goal.steps.count { it.dependsOn.isNotEmpty() }
        return (0.55 + (chained.toDouble() / total.toDouble()) * 0.35).coerceAtMost(0.95)
    }

    // ── Execution history for adaptive strategy ──────────────────────────────

    private data class ExecutionRecord(
        val goalId:        String,
        val stepCount:     Int,
        val successRate:   Float,
        val failedActions: List<String>,
        val timestampMs:   Long = System.currentTimeMillis()
    )

    private val executionHistory = mutableListOf<ExecutionRecord>()

    /** Strategy hints derived from execution history. */
    private var avoidActions = mutableSetOf<String>()
    private var preferSimpleSteps = false
    private var maxStepsOverride: Int? = null

    /**
     * Record the outcome of a plan execution so [adjustStrategy] can learn.
     *
     * @param goalId        The [AgentGoal.id] of the executed plan.
     * @param successCount  Steps that completed successfully.
     * @param totalCount    Total steps in the plan.
     * @param failedActions Action types (e.g. "click", "search") that failed.
     */
    fun recordExecutionOutcome(
        goalId:        String,
        successCount:  Int,
        totalCount:    Int,
        failedActions: List<String> = emptyList()
    ) {
        val rate = if (totalCount == 0) 1f else successCount.toFloat() / totalCount.toFloat()
        executionHistory.add(
            ExecutionRecord(
                goalId        = goalId,
                stepCount     = totalCount,
                successRate   = rate,
                failedActions = failedActions
            )
        )
        // Keep only the last 30 outcomes
        if (executionHistory.size > 30) executionHistory.removeAt(0)
        if (com.airi.assistant.BuildConfig.DEBUG) Log.d(TAG, "Recorded execution: goalId=$goalId rate=$rate failedActions=$failedActions")
    }

    /**
     * Analyse recent execution history and update internal routing hints.
     *
     * REAL BEHAVIOUR:
     *   - If the same action type failed in >30% of recent plans, it is added
     *     to [avoidActions] so future plans skip or replace that step type.
     *   - If the mean success rate over the last 10 plans is <60%, [preferSimpleSteps]
     *     is set — [createDAGPlanFromLLM] will cap plan depth and disable
     *     non-essential intermediate steps.
     *   - If plans with > 5 steps have a lower success rate than plans with ≤ 5
     *     steps, [maxStepsOverride] is set to 4 to force brevity.
     */
    fun adjustStrategy() {
        if (executionHistory.isEmpty()) return

        val recent      = executionHistory.takeLast(10)
        val meanSuccess = recent.map { it.successRate }.average().toFloat()

        // Step-type failure analysis
        val actionFailCounts = mutableMapOf<String, Int>()
        recent.forEach { rec ->
            rec.failedActions.forEach { action ->
                actionFailCounts[action] = (actionFailCounts[action] ?: 0) + 1
            }
        }
        avoidActions = actionFailCounts
            .filter { (_, count) -> count.toFloat() / recent.size > 0.30f }
            .keys.toMutableSet()

        preferSimpleSteps = meanSuccess < 0.60f

        // Length analysis: compare short vs long plan success rates
        val shortPlans = recent.filter { it.stepCount <= 5 }
        val longPlans  = recent.filter { it.stepCount > 5 }
        val shortRate  = shortPlans.map { it.successRate }.average()
        val longRate   = longPlans.map { it.successRate }.average()
        maxStepsOverride = if (longPlans.size >= 3 && longRate < shortRate - 0.20) 4 else null

        Log.i(TAG, "AIRI_PROOF STRATEGY_ADJUSTED " +
            "meanSuccess=$meanSuccess avoidActions=$avoidActions " +
            "preferSimple=$preferSimpleSteps maxSteps=$maxStepsOverride")
    }

    /**
     * Simplify an [AgentGoal] by:
     *   1. Removing steps whose action type is in [avoidActions].
     *   2. Collapsing chains of sequential Wait steps into a single step.
     *   3. Capping the total step count at [maxStepsOverride] (if set) by
     *      keeping root (dependency-free) steps and their immediate children.
     *   4. Re-linearising dependencies after any removal.
     *
     * @return A simplified [AgentGoal]; returns the original if no reduction
     *         was possible or [preferSimpleSteps] is false.
     */
    fun reduceComplexity(goal: AgentGoal): AgentGoal {
        if (!preferSimpleSteps && avoidActions.isEmpty() && maxStepsOverride == null) {
            return goal
        }

        var steps = goal.steps.toMutableList()

        // 1. Remove avoided action types
        if (avoidActions.isNotEmpty()) {
            steps.removeAll { step ->
                when (step) {
                    is PlanStep.Custom -> step.action in avoidActions
                    is PlanStep.Search -> "search" in avoidActions
                    is PlanStep.Click  -> "click" in avoidActions || "tap" in avoidActions
                    else -> false
                }
            }
        }

        // 2. Collapse consecutive Wait steps
        steps = steps.fold(mutableListOf()) { acc, step ->
            val prev = acc.lastOrNull()
            if (step is PlanStep.Wait && prev is PlanStep.Wait) {
                acc.removeAt(acc.size - 1)
                acc.add(prev.copy(
                    durationMs = (prev.durationMs ?: 0L) + (step.durationMs ?: 0L)
                ))
            } else {
                acc.add(step)
            }
            acc
        }

        // 3. Cap step count
        val cap = maxStepsOverride
        if (cap != null && steps.size > cap) {
            val roots    = steps.filter { it.dependsOn.isEmpty() }.take(cap)
            val rootIds  = roots.map { it.id }.toSet()
            val children = steps
                .filter { s -> s.dependsOn.any { it in rootIds } }
                .take((cap - roots.size).coerceAtLeast(0))
            steps = (roots + children).toMutableList()
        }

        // 4. Re-linearise: fix dangling dependency references after removals
        val validIds = steps.map { it.id }.toSet()
        steps = steps.map { step ->
            val cleanDeps = step.dependsOn.filter { it in validIds }
            when (step) {
                is PlanStep.Custom -> step.copy(dependsOn = cleanDeps)
                is PlanStep.OpenApp -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Search -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Click -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Type -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Navigate -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Wait -> step.copy(dependsOn = cleanDeps)
                is PlanStep.Scroll -> step.copy(dependsOn = cleanDeps)
            }
        }.toMutableList()

        Log.i(TAG, "AIRI_PROOF COMPLEXITY_REDUCED originalSteps=${goal.steps.size} reducedSteps=${steps.size}")
        return goal.copy(steps = steps)
    }

    /** Backward-compat no-arg overload (applies strategy hints in place). */
    fun adjustStrategy(noop: Unit = Unit) { adjustStrategy() }

    // ── External adaptation hint injection ───────────────────────────────────

    /**
     * Apply externally computed [PlanAdaptationHints] to this generator.
     *
     * Called by [PlannerAdaptationEngine.applyToGenerator] before every plan
     * generation so that all accumulated persistent learning (failed actions,
     * confidence bands, max-step caps) influences the next plan.
     *
     * Hints are additive:
     *  - [hints.avoidedActions] is UNION'd with any internally computed avoids.
     *  - [hints.preferSimple] OR-s with the internally derived preference.
     *  - [hints.maxStepsHint] takes precedence over the internally computed cap
     *    only if it is more restrictive (smaller).
     */
    fun applyAdaptationHints(hints: PlanAdaptationHints) {
        if (!hints.hasConstraints) return

        // Merge avoided actions (union — both internal and external avoids apply)
        avoidActions.addAll(hints.avoidedActions)

        // OR: external simple preference activates the flag even if internal
        // analysis hasn't yet detected a low mean-success rate
        if (hints.preferSimple) preferSimpleSteps = true

        // Most restrictive cap wins
        val externalCap = hints.maxStepsHint
        if (externalCap != null) {
            val current = maxStepsOverride
            maxStepsOverride = if (current == null) externalCap else minOf(current, externalCap)
        }

        Log.i(TAG, "AIRI_PROOF ADAPTATION_HINTS_APPLIED " +
            "avoidedTotal=${avoidActions.size} preferSimple=$preferSimpleSteps " +
            "maxSteps=$maxStepsOverride overallConfidence=${hints.overallConfidence}")
    }
}
