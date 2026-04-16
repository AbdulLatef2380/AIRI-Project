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
                    appName = params.optString("app_name", params.optString("name", "unknown")),
                    packageName = params.optString("package").takeIf { it.isNotEmpty() },
                    dependsOn = dependsOn,
                    expectedOutcome = expected
                )

                "search", "search_web" -> PlanStep.Search(
                    id = id,
                    query = params.optString("query", ""),
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

    fun adjustStrategy() {
        // TODO: Implement strategy adjustment based on execution history
    }

    fun reduceComplexity() {
        // TODO: Implement complexity reduction algorithm
    }
}
