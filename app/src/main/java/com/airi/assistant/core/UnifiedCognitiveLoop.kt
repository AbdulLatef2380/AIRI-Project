package com.airi.assistant.core

import android.util.Log
import com.airi.assistant.BuildConfig
import com.airi.assistant.agent.execution.command.CommandResult
import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.learning.SkillOutcomeScorer
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.agent.planning.RecoveryBranch
import com.airi.assistant.agent.planning.RecoveryDecision
import com.airi.assistant.agent.planning.TypedPlanGraph
import com.airi.assistant.agent.reflection.ExecutionReflector
import com.airi.assistant.agent.reflection.PlanQualityScorer
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.agent.workspace.SandboxWorkspace
import com.airi.assistant.agent.workspace.WorkspaceRegistry
import com.airi.assistant.domain.monetization.ActionType
import com.airi.assistant.domain.policy.PolicyDecision
import com.airi.assistant.domain.policy.UnifiedPolicyGate
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.perception.CognitiveInput
import com.airi.assistant.perception.PerceptionFusion
import com.airi.assistant.world.WorldState
import com.airi.assistant.world.WorldStateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

/**
 * UnifiedCognitiveLoop — the primary execution engine for TypedPlanGraph DAGs.
 *
 * ── PARALLEL WAVE EXECUTION (Phase 3 upgrade) ────────────────────────────────
 * Previously the inner loop was sequential (`for (node in ready) { ... }`),
 * meaning even independent DAG nodes ran one at a time.
 *
 * Upgrade: each "wave" (set of nodes whose dependencies are all DONE) runs
 * under `supervisorScope { async { } }.awaitAll()`. Node-level isolation via
 * supervisorScope ensures that one node throwing an exception does not cancel
 * its siblings. CancellationException still propagates so the parent scope
 * can kill the graph cleanly.
 *
 * After `awaitAll()` returns, results are processed sequentially to avoid
 * concurrent TypedPlanGraph mutation (though the graph is already @Synchronized,
 * deterministic ordering avoids surprising Abort vs Skip races).
 *
 * ── CHAOS HARDENING ──────────────────────────────────────────────────────────
 * - `workspace.snapshot()` is called before EVERY wave (not just once at start),
 *   creating fine-grained atomic checkpoints for post-crash resume.
 * - `WorkspaceRegistry.release(graph.goalId)` is in a `finally` block — always
 *   fires whether the loop completes, throws, or is cancelled.
 * - `graph.resetForRetry(node.id)` replaces the broken `continue` trick in the
 *   old sequential loop: sets the node back to PENDING without resetting its
 *   attempt counter, so the retry budget is correctly enforced.
 * - `abortFlag` collects Abort decisions from the parallel wave before
 *   returning, so the early-exit happens AFTER all wave results are processed
 *   (prevents races where a slow node overwrites a fast node's Abort decision).
 *
 * ── REFLECTION LOOP (Phase 2) ─────────────────────────────────────────────────
 * - [PlanQualityScorer.score] runs before the first wave. Plans scoring below
 *   the confidence threshold are rejected and the caller receives a failure
 *   result with a self-critique message, avoiding wasted execution of bad plans.
 * - [ExecutionReflector.reflect] runs after graph completion (or failure) and
 *   produces a [ReflectionReport] — failure pattern analysis, action-type
 *   success rates, self-critique text, and an updated confidence score.
 *
 * ── EXECUTION STATUS BUS (Phase 5) ───────────────────────────────────────────
 * [ExecutionStatusBus] is updated at every major lifecycle boundary so the UI
 * (ChatViewModel → ChatScreen) can render a live execution progress indicator
 * without polling or direct coupling to UCL.
 */
class UnifiedCognitiveLoop {
    companion object {
        private const val TAG = "UnifiedCognitiveLoop"

        /** Plans scoring below this confidence threshold are rejected pre-execution. */
        private const val MIN_PLAN_CONFIDENCE = 0.35f
    }

    /**
     * Bug-6 fix: recent conversation turns injected by the caller (ChatViewModel)
     * before each process() call. runNode() propagates these into every SubAgentContext
     * so that CodingAgent, ResearchAgent, and other LLM-backed agents receive
     * conversation history and produce contextually coherent responses.
     *
     * @Volatile guarantees visibility across the IO dispatcher's threads. Assignment
     * is atomic on JVM (reference write) so no additional locking is needed.
     */
    @Volatile
    var recentTurns: List<String> = emptyList()

    val planGenerator = PlanGenerator()

    private val outcomeScorer: SkillOutcomeScorer?
        by lazy { runCatching { ServiceLocator.skillOutcomeScorer }.getOrNull() }
    private val worldStateManager: WorldStateManager?
        by lazy { runCatching { ServiceLocator.context?.let { WorldStateManager(it) } }.getOrNull() }

    private val planQualityScorer = PlanQualityScorer()
    private val reflector         = ExecutionReflector()

    /** Singleton adaptation engine — accumulates learning across UCL instances. */
    private val adaptationEngine
        by lazy { runCatching { ServiceLocator.plannerAdaptationEngine }.getOrNull() }

    // ── Public entry points ───────────────────────────────────────────────────

    suspend fun process(input: String): CognitiveResult =
        processPercept(PerceptionFusion.fromText(input))

    suspend fun processCognitiveInput(input: CognitiveInput): CognitiveResult =
        processPercept(input)

    /**
     * Primary LLM-assisted execution path: parses the LLM's response into a
     * typed DAG plan and drives it through [executeGraph] (parallel wave
     * scheduling, real sub-agent routing via [SubAgentRegistry], recovery
     * branches, live [ExecutionStatusBus] updates).
     *
     * Previously used the flat [executeActionPlan] path which bypassed
     * [TypedPlanGraph], parallel scheduling, and all DAG-level recovery.
     */
    suspend fun process(input: BrainInput, llmResponse: String): CognitiveResult {
        adaptationEngine?.applyToGenerator(planGenerator)
        val actionPlan = planGenerator.createDAGPlanFromLLM(llmResponse, input.text)
        if (actionPlan.steps.isEmpty()) {
            return CognitiveResult.Failed(actionPlan, emptyList(), "Empty plan from LLM response")
        }
        val graph = TypedPlanGraph(
            goalId      = "ucl_${System.currentTimeMillis()}",
            description = actionPlan.intent.take(120)
        )
        for (step in actionPlan.steps) {
            val actionName: String
            val params: Map<String, String>
            when (step) {
                is PlanStep.Custom   -> { actionName = step.action;   params = step.parameters }
                is PlanStep.OpenApp  -> { actionName = "open_app";    params = mapOf("app" to step.appName) }
                is PlanStep.Search   -> { actionName = "search";      params = mapOf("query" to step.query) }
                is PlanStep.Click    -> { actionName = "click";       params = mapOf("target" to step.targetText) }
                is PlanStep.Type     -> { actionName = "type_text";   params = mapOf("text" to step.text) }
                is PlanStep.Navigate -> { actionName = "navigate";    params = mapOf("direction" to step.direction.name) }
                is PlanStep.Wait     -> { actionName = "wait";        params = mapOf("duration_ms" to (step.durationMs ?: 1000L).toString()) }
                is PlanStep.Scroll   -> { actionName = "scroll";      params = mapOf("direction" to step.direction.name) }
            }
            graph.addNode(GoalNode(
                id             = step.id,
                description    = actionName,
                action         = actionName,
                params         = params,
                dependsOn      = step.dependsOn,
                recoveryBranch = RecoveryBranch.Retry(2),
                isCritical     = step.dependsOn.isNotEmpty()
            ))
        }
        val result = executeGraph(graph)
        // Bug-4 fix: previously returned emptyList() for node results, so any
        // text synthesized by agents (via llmDelegate) was silently discarded
        // even after the Bug-1 fix made runNode capture it. Now we propagate
        // the full set of NodeExecutionRecords as StepResults so the caller
        // (ChatViewModel) can extract and surface synthesized text to the user.
        val stepResults = result.nodeResults.map { record ->
            StepResult(
                step   = PlanStep.Custom(
                    id              = record.node.id,
                    action          = record.node.activeAction,
                    parameters      = record.node.activeParams,
                    dependsOn       = record.node.dependsOn,
                    expectedOutcome = record.node.expectedOutcome ?: ""
                ),
                result = CommandResult(record.success, record.message ?: "")
            )
        }
        return if (result.success)
            CognitiveResult.Success(actionPlan, stepResults)
        else
            CognitiveResult.Failed(
                actionPlan,
                stepResults,
                result.rejectionReason ?: "Graph execution failed"
            )
    }

    // ── Graph execution ───────────────────────────────────────────────────────

    /**
     * Execute a [TypedPlanGraph] end-to-end using parallel wave scheduling.
     *
     * Each wave executes all currently-ready nodes in parallel under
     * `supervisorScope { async { } }.awaitAll()`. Results are processed
     * sequentially after each wave to maintain graph consistency.
     *
     * An atomic workspace snapshot is taken before each wave so that a
     * process death mid-wave can be recovered by rolling back to the last
     * clean wave boundary.
     *
     * [ExecutionStatusBus] is updated at every major lifecycle boundary:
     *   • onGraphStarted  — before the wave loop
     *   • onWaveStarted   — before each parallel wave
     *   • onNodeCompleted — after each successful node
     *   • onNodeRecovering — on Retry recovery decisions
     *   • onReflecting    — before post-graph reflection
     *   • onGraphCompleted — after reflection (or on quality-gate rejection)
     *
     * @param graph     The DAG to execute. All @Synchronized methods are safe
     *                  to call from the parallel node coroutines.
     * @param workspace Defaults to the WorkspaceRegistry entry for [graph.goalId].
     *                  Released in a `finally` block regardless of outcome.
     */
    suspend fun executeGraph(
        graph:     TypedPlanGraph,
        workspace: SandboxWorkspace = WorkspaceRegistry.get(graph.goalId)
    ): GraphExecutionResult {
        // ── Phase 2: Pre-execution plan quality gate ──────────────────────────
        val qualityScore = planQualityScorer.score(graph)
        if (qualityScore.confidence < MIN_PLAN_CONFIDENCE) {
            Log.w(TAG, "PLAN_QUALITY_GATE_REJECT confidence=${qualityScore.confidence} reason=${qualityScore.critique}")
            WorkspaceRegistry.release(graph.goalId)
            ExecutionStatusBus.onGraphCompleted(false)
            return GraphExecutionResult(
                goalId        = graph.goalId,
                success       = false,
                nodeResults   = emptyList(),
                graphSnapshot = graph.snapshot(),
                workspace     = workspace,
                reflection    = null,
                rejectionReason = "Plan quality below threshold (${qualityScore.confidence}): ${qualityScore.critique}"
            )
        }

        // ── Phase 5: Signal graph start to UI ────────────────────────────────
        val initialSnapshot = graph.snapshot()
        ExecutionStatusBus.onGraphStarted(
            goalDescription = graph.description.ifBlank { graph.goalId },
            totalNodes      = initialSnapshot.totalNodes
        )

        val nodeResults     = mutableListOf<NodeExecutionRecord>()
        val hub             = runCatching { ServiceLocator.observabilityHub }.getOrNull()
        var nodesCompleted  = 0

        try {
            var abortFlag = false

            while (!graph.isComplete() && !graph.isFailed() && !abortFlag) {
                val ready = graph.readyNodes()
                if (ready.isEmpty()) break

                // ── Atomic wave checkpoint BEFORE execution ───────────────────
                // Any process death during the wave can be recovered by rolling
                // back to this snapshot (last clean wave boundary).
                workspace.snapshot()

                // ── Policy gate + markRunning (sequential — graph is @Synchronized) ──
                val waveNodes = mutableListOf<GoalNode>()
                for (node in ready) {
                    val decision = checkNodePolicy(node)
                    if (decision is PolicyDecision.Deny) {
                        workspace.logPolicyCheck(node.action, "check", false)
                        graph.markFailed(node.id, "policy:${decision.reason}")
                        nodeResults.add(NodeExecutionRecord(node, false, decision.userMessage))
                        Log.w(TAG, "POLICY_DENY node=${node.id} reason=${decision.reason}")
                    } else {
                        graph.markRunning(node.id)
                        waveNodes += node
                    }
                }

                hub?.updateGraphSnapshot(graph.snapshot())
                if (waveNodes.isEmpty()) continue

                Log.i(TAG, "WAVE_START nodes=${waveNodes.size} ids=${waveNodes.map { it.id }}")

                // ── Phase 5: Signal wave start to UI ─────────────────────────
                ExecutionStatusBus.onWaveStarted(
                    nodeIds     = waveNodes.map { it.id },
                    nodeActions = waveNodes.map { it.activeAction }
                )

                // ── Parallel node execution ───────────────────────────────────
                // supervisorScope: a node failure does not cancel siblings.
                // CancellationException from external scope cancellation still propagates.
                val waveResults = supervisorScope {
                    waveNodes.map { node ->
                        async {
                            val startMs = System.currentTimeMillis()
                            val result = runCatching { runNode(node) }.getOrElse { e ->
                                if (e is CancellationException) throw e
                                CommandResult(false, "node_exception:${e.message?.take(120)}")
                            }
                            Triple(node, result, System.currentTimeMillis() - startMs)
                        }
                    }.map { it.await() }  // awaitAll equivalent — supervisorScope catches individually
                }

                // ── Sequential result processing ──────────────────────────────
                // Graph mutations are @Synchronized but we process in order for
                // deterministic Abort/Skip precedence.
                for ((node, result, latency) in waveResults) {
                    outcomeScorer?.record(
                        skillName   = node.action,
                        success     = result.success,
                        latencyMs   = latency,
                        errorReason = if (!result.success) result.message else null
                    )

                    if (result.success) {
                        graph.markDone(node.id, result.message)
                        nodeResults.add(NodeExecutionRecord(node, true, result.message))
                        Log.i(TAG, "AIRI_PROOF GRAPH_NODE_DONE id=${node.id} latency=${latency}ms")
                        // Phase 5: update live progress counter
                        ExecutionStatusBus.onNodeCompleted(node.id, ++nodesCompleted)
                    } else {
                        when (val recovery = graph.markFailed(node.id, result.message ?: "unknown")) {
                            is RecoveryDecision.Retry -> {
                                // Put node back to PENDING so the next wave picks it up.
                                // Attempt counter is preserved (NOT reset) so the retry budget
                                // is enforced across waves. The old sequential implementation
                                // used `continue` inside the for-loop, which silently left the
                                // node in RECOVERING and never actually retried it.
                                graph.resetForRetry(node.id)
                                Log.i(TAG, "GRAPH_RETRY_QUEUED id=${node.id} attempt=${node.attempts}")
                                // Phase 5: signal recovery state to UI
                                ExecutionStatusBus.onNodeRecovering(
                                    nodeId     = node.id,
                                    reason     = result.message ?: "retry",
                                    retryCount = node.attempts
                                )
                            }
                            is RecoveryDecision.RequestReplan -> {
                                Log.i(TAG, "GRAPH_REPLAN id=${node.id} reason=${recovery.reason}")
                                if (!repatchNode(graph, node, recovery.reason)) {
                                    abortFlag = true
                                }
                            }
                            is RecoveryDecision.Abort -> {
                                Log.w(TAG, "GRAPH_ABORT id=${node.id} reason=${recovery.reason}")
                                abortFlag = true
                            }
                            is RecoveryDecision.Skip -> Unit
                        }
                        nodeResults.add(NodeExecutionRecord(node, false, result.message))
                    }
                }

                hub?.updateGraphSnapshot(graph.snapshot())
                Log.i(TAG, "WAVE_DONE nodes=${waveNodes.size} abortFlag=$abortFlag")
            }

        } finally {
            // Always release — covers normal completion, early abort, and
            // coroutine cancellation (process death, ViewModel.onCleared, etc.)
            WorkspaceRegistry.release(graph.goalId)
        }

        val finalSnapshot = graph.snapshot()
        hub?.updateGraphSnapshot(finalSnapshot)

        // ── Phase 2: Post-execution reflection ────────────────────────────────
        ExecutionStatusBus.onReflecting()
        val reflection = reflector.reflect(nodeResults, finalSnapshot)
        Log.i(TAG, "REFLECTION confidence=${reflection.executionConfidence} critique=${reflection.critiqueText.take(80)}")

        // ── Phase 1–4: Closed-loop adaptation ─────────────────────────────────
        // Ingest every execution result into the persistent adaptation engine so
        // future plans avoid failed action types, quarantine unreliable agents,
        // prefer effective recovery strategies, and cap complexity under stress.
        adaptationEngine?.ingest(
            report      = reflection,
            nodeResults = nodeResults,
            goalId      = graph.goalId
        )
        // Immediately push updated hints back into the planGenerator so the NEXT
        // call to createDAGPlanFromLLM on this UCL instance already reflects
        // what was just learned (not just after the next restart).
        adaptationEngine?.applyToGenerator(planGenerator)

        // ── Phase 5: Signal graph completion to UI ────────────────────────────
        val graphSuccess = finalSnapshot.failedNodes == 0
        ExecutionStatusBus.onGraphCompleted(graphSuccess)

        return GraphExecutionResult(
            goalId        = graph.goalId,
            success       = graphSuccess,
            nodeResults   = nodeResults,
            graphSnapshot = finalSnapshot,
            workspace     = workspace,
            reflection    = reflection
        )
    }

    // ── Internal: flat ActionPlan execution ───────────────────────────────────

    private suspend fun processPercept(input: CognitiveInput): CognitiveResult {
        // Bug-5 fix: old implementation called createDAGPlanFromLLM with a hard-coded
        // single-step JSON (not a real LLM response) then ran the result through the
        // old FLAT executeActionPlan path, bypassing TypedPlanGraph, parallel wave
        // scheduling, the policy gate, recovery branches, reflection, and adaptation.
        //
        // Replacement: build a single-node TypedPlanGraph whose action text is the
        // raw percept input.  runNode() routes this through SubAgentRegistry so the
        // correct registered agent handles it.  If no agent matches, CommandRouter
        // Tier-1 alias mapping or Tier-3 failure-signal handles it gracefully.
        // Full pipeline (policy gate → wave → recovery → reflection → adaptation)
        // now applies to every processPercept call.
        adaptationEngine?.applyToGenerator(planGenerator)
        val goalId = "percept_${System.currentTimeMillis()}"
        val graph = TypedPlanGraph(goalId = goalId, description = input.primaryText.take(120))
        graph.addNode(GoalNode(
            id             = "${goalId}_n0",
            description    = input.primaryText.take(200),
            action         = input.primaryText.take(200),
            params         = emptyMap(),
            dependsOn      = emptyList(),
            recoveryBranch = RecoveryBranch.Retry(1),
            isCritical     = false
        ))
        val result = executeGraph(graph)
        val dummyPlan = ActionPlan(
            intent     = input.primaryText,
            confidence = if (result.success) 1.0 else 0.0,
            steps      = emptyList()
        )
        return if (result.success)
            CognitiveResult.Success(dummyPlan, emptyList())
        else
            CognitiveResult.Failed(dummyPlan, emptyList(),
                result.rejectionReason ?: "Percept graph execution failed")
    }

    private suspend fun executeActionPlan(actionPlan: ActionPlan, worldState: WorldState?): CognitiveResult {
        val results = mutableListOf<StepResult>()
        for (step in actionPlan.steps) {
            val result = CommandRouter.execute(step)
            results.add(StepResult(step, result))
            outcomeScorer?.record(
                skillName   = step::class.simpleName ?: "step",
                success     = result.success,
                errorReason = if (!result.success) result.message else null
            )
            if (!result.success && isCriticalFailure(step)) {
                return CognitiveResult.Failed(actionPlan, results, result.message ?: "Failed")
            }
        }
        return if (results.all { it.result.success })
            CognitiveResult.Success(actionPlan, results)
        else
            CognitiveResult.PartialSuccess(actionPlan, results)
    }

    // ── Internal: node execution ──────────────────────────────────────────────

    /**
     * Execute a single [GoalNode] via two-tier dispatch:
     *   Tier 1 — SubAgentRegistry: keyword-score routing to tool agents.
     *   Tier 2 — CommandRouter: accessibility / UI command fallback.
     *
     * This function is called from inside a `supervisorScope async` block and
     * must NEVER throw (except CancellationException). All errors are returned
     * as CommandResult(false, ...). The caller wraps it in runCatching anyway
     * for defence-in-depth.
     */
    private suspend fun runNode(node: GoalNode): CommandResult {
        val routingInput = buildString {
            append(node.activeAction.replace('_', ' '))
            if (node.activeParams.isNotEmpty()) {
                append(" ")
                append(node.activeParams.entries.joinToString(" ") { (k, v) -> "$k:$v" })
            }
        }
        val context = SubAgentContext(
            sessionId          = node.id,
            userId             = "cognitive_loop",
            recentTurns        = recentTurns,   // Bug-6 fix: propagate conversation history
            worldState         = emptyMap(),
            grantedPermissions = SubAgentRegistry.activeCapabilities(),
            nestingDepth       = 1,
            dependencyResults  = node.activeParams,
            privacyLevel       = resolvePrivacyLevel()
        )

        val agent = SubAgentRegistry.route(routingInput, context)
        if (agent != null) {
            Log.i(TAG, "runNode: routed id=${node.id} action=${node.activeAction} agent=${agent.capability.agentId}")
            var resultText = ""
            var failed     = false
            var failReason = ""
            runCatching {
                agent.execute(routingInput, context).collect { event ->
                    when (event) {
                        is AgentEvent.Complete      -> resultText = event.result
                        is AgentEvent.PartialResult -> resultText += event.text
                        is AgentEvent.Failed        -> { failed = true; failReason = event.reason }
                        is AgentEvent.Delegate      -> {
                            // Bug-1 fix: previously `else -> Unit` swallowed all Delegate events,
                            // silently dropping the LLM synthesis requested by CodingAgent,
                            // ResearchAgent, CloudBrowserAgent, and ReActPlanner when those
                            // agents are invoked through the graph execution path (runNode).
                            // We now resolve the llmDelegate wired by ChatViewModel.wireLlmDelegate()
                            // and use its result as this node's output text.
                            if (event.targetAgentId == "llm_backend") {
                                val llmResult = runCatching {
                                    ServiceLocator.productionOrchestrator.llmDelegate
                                        ?.invoke(event.subInput) ?: ""
                                }.getOrElse { e ->
                                    Log.w(TAG, "runNode llmDelegate failed: ${e.message}")
                                    ""
                                }
                                if (llmResult.isNotBlank()) resultText = llmResult
                            }
                        }
                        is AgentEvent.ToolCall      -> if (BuildConfig.DEBUG) Log.d(TAG, "runNode tool=${event.toolName}")
                        else                        -> Unit
                    }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                Log.e(TAG, "runNode agent threw: ${e.message}")
                failed = true; failReason = e.message ?: "agent exception"
            }
            return if (failed) CommandResult(false, failReason)
            else CommandResult(true, resultText.ifBlank { "node:${node.id}:done" })
        }

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "runNode: no agent for id=${node.id} action=${node.activeAction} — fallback to CommandRouter")
        }
        return CommandRouter.execute(
            PlanStep.Custom(node.id, node.activeAction, node.activeParams, node.dependsOn, node.expectedOutcome)
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun captureWorldState(): WorldState? =
        runCatching { worldStateManager?.getCurrentState() }.getOrNull()

    private fun buildPromptJson(input: CognitiveInput): String {
        val escapedText = input.primaryText.replace("\"", "\\\"").take(200)
        return """{"goal":"$escapedText","steps":[{"id":"1","action":"$escapedText","params":{},"depends_on":[]}]}"""
    }

    private fun isCriticalFailure(step: PlanStep): Boolean =
        step !is PlanStep.Wait && step !is PlanStep.Custom

    private fun checkNodePolicy(node: GoalNode): PolicyDecision = try {
        UnifiedPolicyGate.check(
            ServiceLocator.creditMeteringEngine,
            ServiceLocator.permissionService,
            outcomeScorer,
            node.action,
            ActionType.AGENT_EXECUTION
        )
    } catch (_: Throwable) {
        PolicyDecision.Allow(9999, SkillOutcomeScorer.ToolPolicy.NORMAL)
    }

    private fun repatchNode(graph: TypedPlanGraph, node: GoalNode, reason: String): Boolean {
        val lower = reason.lowercase()
        val fallbackAction = when {
            lower.contains("network") || lower.contains("timeout")       -> "wait_and_retry"
            lower.contains("permission")                                  -> "conversation"
            lower.contains("not found") || lower.contains("no agent")    -> "conversation"
            node.activeAction.startsWith("search")                       -> "conversation"
            node.activeAction.startsWith("open")                         -> "conversation"
            else                                                         -> null
        }
        return if (fallbackAction != null && fallbackAction != node.activeAction) {
            graph.patchNode(node.id, fallbackAction, node.activeParams)
            Log.i(TAG, "repatchNode: ${node.activeAction} → $fallbackAction " +
                "reason=${reason.take(60)}")
            true
        } else {
            Log.w(TAG, "repatchNode: no viable fallback for " +
                "action=${node.activeAction} — returning false to trigger abort")
            false
        }
    }

    /**
     * Reads the user's active [ExecutionMode] and maps it to a
     * [SubAgentContext] privacy level, preventing LOCAL_ONLY bypass.
     */
    private fun resolvePrivacyLevel(): Int = runCatching {
        val ctx = ServiceLocator.context ?: return@runCatching SubAgentContext.PRIVACY_STANDARD
        val mode = ExecModePreferences(ctx).effectiveMode
        if (mode == ExecutionMode.LOCAL_ONLY) SubAgentContext.PRIVACY_MAXIMUM
        else SubAgentContext.PRIVACY_STANDARD
    }.getOrDefault(SubAgentContext.PRIVACY_STANDARD)
}

// ── Result types ──────────────────────────────────────────────────────────────

data class NodeExecutionRecord(
    val node:    GoalNode,
    val success: Boolean,
    val message: String?
)

data class GraphExecutionResult(
    val goalId:          String,
    val success:         Boolean,
    val nodeResults:     List<NodeExecutionRecord>,
    val graphSnapshot:   com.airi.assistant.agent.planning.GraphSnapshot,
    val workspace:       SandboxWorkspace,
    val reflection:      com.airi.assistant.agent.reflection.ReflectionReport? = null,
    val rejectionReason: String? = null
)

data class StepResult(val step: PlanStep, val result: CommandResult)

sealed class CognitiveResult {
    data class Success(val plan: ActionPlan, val results: List<StepResult>) : CognitiveResult()
    data class PartialSuccess(val plan: ActionPlan, val results: List<StepResult>) : CognitiveResult()
    data class Failed(val plan: ActionPlan, val results: List<StepResult>, val reason: String) : CognitiveResult()
    data class AwaitingConfirmation(val plan: ActionPlan) : CognitiveResult()
    data class Error(val message: String) : CognitiveResult()
}
