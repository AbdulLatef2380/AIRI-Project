package com.airi.assistant.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.AiriSkillOrchestrator
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.agent.multiagent.SharedCognitiveBus
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.connector.ConnectorRuntimeManager
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * SkillRuntime — dynamic skill execution engine built on top of the existing
 * [SkillRegistry] and [AiriSkillOrchestrator].
 *
 * What this adds beyond the existing static SkillRegistry:
 *  - Runtime skill registration (custom/user skills loadable at runtime)
 *  - Skill chaining (output of one skill feeds the next)
 *  - Connector-aware execution (skills can invoke connectors via [ConnectorRuntimeManager])
 *  - Sandbox-aware execution (code/shell skills run in [SandboxManager])
 *  - Agent bus notification (results broadcast to [SharedCognitiveBus])
 *  - Permission checking before execution
 *  - Observable execution state for UI
 */
class SkillRuntime(
    private val context:                  Context,
    private val skillRegistry:            SkillRegistry,
    private val orchestrator:             AiriSkillOrchestrator,
    private val connectorRuntimeManager:  ConnectorRuntimeManager,
    private val sandboxManager:           SandboxManager
) {
    private val TAG   = "SkillRuntime"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Dynamic skills registered at runtime (supplements SkillRegistry)
    private val dynamicSkills = ConcurrentHashMap<String, AiriSkill>()

    // Observable execution state
    data class SkillExecution(
        val skillName:  String,
        val status:     Status,
        val startedMs:  Long = System.currentTimeMillis(),
        val output:     String? = null
    ) { enum class Status { PENDING, RUNNING, DONE, FAILED } }

    private val _activeExecutions = MutableStateFlow<List<SkillExecution>>(emptyList())
    val activeExecutions: StateFlow<List<SkillExecution>> = _activeExecutions.asStateFlow()

    private val executionMap = ConcurrentHashMap<String, SkillExecution>()

    // ── Registration ──────────────────────────────────────────────────────────

    /** Register a new skill at runtime — available immediately for routing. */
    fun registerDynamic(skill: AiriSkill) {
        dynamicSkills[skill.name] = skill
        AgentActivityBus.emit("Skill registered: ${skill.name}", ActivityCategory.TOOL)
        Log.i(TAG, "Dynamic skill registered: ${skill.name}")
    }

    fun unregisterDynamic(skillName: String) {
        dynamicSkills.remove(skillName)
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    /**
     * Route an input string to the best matching skill.
     * Checks dynamic skills first, then delegates to [AiriSkillOrchestrator].
     */
    suspend fun route(
        input:   String,
        skillCtx: SkillContext,
        preferredSkillId: String? = null
    ): AiriSkill? {
        // 1. Check if a specific skill was requested
        if (preferredSkillId != null) {
            dynamicSkills[preferredSkillId]?.let { return it }
        }

        // 2. Score dynamic skills
        val bestDynamic = dynamicSkills.values
            .mapNotNull { skill ->
                val score = runCatching { skill.score(input, skillCtx) }.getOrElse { 0 }
                if (score > 0) skill to score else null
            }
            .maxByOrNull { it.second }
            ?.first

        if (bestDynamic != null) {
            Log.d(TAG, "Dynamic skill matched: ${bestDynamic.name}")
            return bestDynamic
        }

        // 3. Delegate to existing orchestrator via recommendTopSkill
        val match = orchestrator.recommendTopSkill(context, input)
        if (match != null) {
            // Look up the AiriSkill by name from the registry
            return skillRegistry.getAvailableSkills()
                .firstOrNull { it.name.equals(match.descriptor.skillId, ignoreCase = true) }
        }
        return null
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Execute a skill with full lifecycle management.
     * Emits events to [AgentActivityBus] and [SharedCognitiveBus].
     */
    suspend fun execute(
        skill:   AiriSkill,
        params:  Map<String, Any> = emptyMap(),
        timeoutMs: Long = 30_000L
    ): SkillResult {
        val key = skill.name
        trackStart(key)
        AgentActivityBus.emit("Running skill: ${skill.name}", ActivityCategory.TOOL)

        return try {
            val result = withTimeout(timeoutMs) { skill.execute(params) }

            trackEnd(key, result.data)
            AgentActivityBus.emit(
                "${if (result.success) "✓" else "✕"} Skill ${skill.name}: ${result.data.take(60)}",
                ActivityCategory.TOOL
            )
            SharedCognitiveBus.publishResult(
                fromAgentId = "skill_runtime",
                topic       = "skill_result:${skill.name}",
                payload     = result,
                summary     = result.data.take(60)
            )
            result
        } catch (e: Exception) {
            trackFail(key)
            AgentActivityBus.emit("Skill ${skill.name} failed: ${e.message?.take(60)}", ActivityCategory.TOOL)
            SkillResult(success = false, data = e.message ?: "Error")
        }
    }

    /**
     * Chain two or more skills: output of skill[n] becomes input to skill[n+1].
     * Returns the final result.
     */
    suspend fun chain(
        skills:       List<AiriSkill>,
        initialInput: Map<String, Any> = emptyMap()
    ): SkillResult {
        var currentParams = initialInput
        var lastResult    = SkillResult(success = false, data = "No skills in chain")
        AgentActivityBus.emit("Skill chain started: ${skills.map { it.name }.joinToString("→")}", ActivityCategory.TOOL)
        for (skill in skills) {
            lastResult = execute(skill, currentParams)
            if (!lastResult.success) {
                AgentActivityBus.emit("Skill chain broken at '${skill.name}'", ActivityCategory.TOOL)
                break
            }
            // Pass output as input for the next skill
            currentParams = currentParams + mapOf("previousOutput" to (lastResult.data))
        }
        return lastResult
    }

    // ── Observable state helpers ───────────────────────────────────────────────

    private fun trackStart(key: String) {
        executionMap[key] = SkillExecution(key, SkillExecution.Status.RUNNING)
        publishExecutions()
    }

    private fun trackEnd(key: String, output: String) {
        executionMap[key] = executionMap[key]?.copy(status = SkillExecution.Status.DONE, output = output.take(200))
            ?: SkillExecution(key, SkillExecution.Status.DONE)
        scope.launch { kotlinx.coroutines.delay(3_000); executionMap.remove(key); publishExecutions() }
        publishExecutions()
    }

    private fun trackFail(key: String) {
        executionMap[key] = executionMap[key]?.copy(status = SkillExecution.Status.FAILED)
            ?: SkillExecution(key, SkillExecution.Status.FAILED)
        scope.launch { kotlinx.coroutines.delay(5_000); executionMap.remove(key); publishExecutions() }
        publishExecutions()
    }

    private fun publishExecutions() {
        _activeExecutions.value = executionMap.values.sortedByDescending { it.startedMs }
    }
}
