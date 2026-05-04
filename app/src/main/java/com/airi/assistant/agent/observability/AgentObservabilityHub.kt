package com.airi.assistant.agent.observability

import android.util.Log
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.planning.GraphSnapshot
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.NodeStatus
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.voice.LiveVoiceSession
import com.airi.assistant.voice.VoicePipelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AgentObservabilityHub — unified runtime observability aggregator.
 */
class AgentObservabilityHub {

    private val TAG = "AgentObservabilityHub"
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _snapshot = MutableStateFlow(ObservabilitySnapshot())
    val snapshot: StateFlow<ObservabilitySnapshot> = _snapshot.asStateFlow()

    private val errorRing = mutableListOf<ErrorRecord>()
    private fun addError(record: ErrorRecord) {
        errorRing.add(record)
        if (errorRing.size > 50) errorRing.removeAt(0)
    }

    private val activeSpanMap = java.util.concurrent.ConcurrentHashMap<String, TraceSpan>()
    private val completedSpans = mutableListOf<TraceSpan>()
    private val MAX_COMPLETED_SPANS = 100

    private val toolCallCounts = mutableMapOf<String, Int>()
    private val agentExecCounts = mutableMapOf<String, Int>()
    private val agentErrCounts = mutableMapOf<String, Int>()
    private val agentLastLatency = mutableMapOf<String, Long>()

    @Volatile private var sessionTurns = 0
    @Volatile private var sessionTools = 0
    @Volatile private var sessionTokens = 0

    /**
     * Per-session voice collector scope. Each call to [attachVoiceSession]
     * cancels the previous scope before launching new collectors — prevents
     * accumulation of stale collectors on accessibility reconnect storms.
     *
     * Pattern: child scope of hubScope so cancelling voiceSessionScope does NOT
     * cancel the parent hubScope. SupervisorJob isolates failures per-collector.
     */
    @Volatile private var voiceSessionScope: CoroutineScope? = null

    fun attachVoiceSession(session: LiveVoiceSession) {
        // Cancel previous voice session collectors before attaching new ones.
        // Without this, every accessibility reconnect leaks 3 collect coroutines.
        voiceSessionScope?.cancel()
        val scope = CoroutineScope(hubScope.coroutineContext + SupervisorJob())
        voiceSessionScope = scope

        scope.launch {
            session.state.collect { state -> update { copy(voiceState = state) } }
        }
        scope.launch {
            session.latency.collect { lat ->
                update {
                    copy(
                        lastSttLatencyMs   = lat.lastSttLatencyMs,
                        lastTtsFirstByteMs = lat.lastTtsFirstByteMs,
                        perceivedLatencyMs = lat.perceivedLatencyMs
                    )
                }
            }
        }
        scope.launch {
            session.metrics.collect { m ->
                update {
                    copy(
                        sessionInterruptions = m.interruptionCount,
                        sessionVoiceErrors   = m.errorCount
                    )
                }
            }
        }
        Log.d(TAG, "VOICE_SESSION_ATTACHED — previous collectors cancelled, new scope started")
    }

    fun attachOrchestrator(orchestrator: ProductionAgentOrchestrator) {
        hubScope.launch {
            orchestrator.state.collect { state ->
                val (status, progress) = when (state) {
                    ProductionAgentOrchestrator.OrchestratorState.Idle -> OrchestratorStatus.IDLE to 0
                    is ProductionAgentOrchestrator.OrchestratorState.Running -> OrchestratorStatus.RUNNING to state.progressPercent
                }
                update { copy(orchestratorState = status, orchestratorProgress = progress) }
            }
        }
    }

    fun updateGraphSnapshot(graphSnapshot: GraphSnapshot?) {
        hubScope.launch {
            update { copy(graphSnapshot = graphSnapshot) }
        }
    }

    fun recordToolCall(toolName: String) {
        hubScope.launch {
            toolCallCounts[toolName] = (toolCallCounts[toolName] ?: 0) + 1
            sessionTools++
            update { copy(toolCallCounts = this@AgentObservabilityHub.toolCallCounts.toMap(), sessionTotalToolCalls = sessionTools) }
        }
    }

    fun recordAgentSuccess(agentId: String, durationMs: Long, tokenCount: Int = 0) {
        hubScope.launch {
            agentExecCounts[agentId] = (agentExecCounts[agentId] ?: 0) + 1
            agentLastLatency[agentId] = durationMs
            sessionTurns++
            sessionTokens += tokenCount
            update {
                copy(
                    agentExecutionCounts = this@AgentObservabilityHub.agentExecCounts.toMap(),
                    agentLastLatencyMs = this@AgentObservabilityHub.agentLastLatency.toMap(),
                    sessionTotalTurns = this@AgentObservabilityHub.sessionTurns,
                    sessionTokensConsumed = this@AgentObservabilityHub.sessionTokens
                )
            }
        }
    }

    fun recordAgentError(agentId: String, reason: String) {
        hubScope.launch {
            agentErrCounts[agentId] = (agentErrCounts[agentId] ?: 0) + 1
            addError(ErrorRecord(agentId = agentId, reason = reason, timestampMs = System.currentTimeMillis()))
            update {
                copy(
                    agentErrorCounts = this@AgentObservabilityHub.agentErrCounts.toMap(),
                    recentErrors = this@AgentObservabilityHub.errorRing.toList()
                )
            }
        }
    }

    fun updateDurableTasks(tasks: List<DurableTask>) {
        hubScope.launch {
            update {
                copy(
                    durableTasksActive = tasks.count { !it.isTerminal },
                    durableTasksCompleted = tasks.count { it.status == DurableTaskStatus.COMPLETED },
                    durableTasksFailed = tasks.count { it.status == DurableTaskStatus.FAILED },
                    durableTaskQueue = tasks.filter { !it.isTerminal }
                )
            }
        }
    }

    fun updateMemoryMetrics(episodic: Int, semantic: Int, longTerm: Int) {
        hubScope.launch {
            update {
                copy(
                    episodicMemoryEntries = episodic,
                    semanticMemoryEntries = semantic,
                    longTermMemoryEntries = longTerm
                )
            }
        }
    }

    fun refreshRegistrySnapshot() {
        hubScope.launch { update { copy(registeredAgents = SubAgentRegistry.capabilities()) } }
    }

    fun startSpan(name: String, parentSpanId: String? = null, attributes: Map<String, String> = emptyMap()): String {
        val spanId = java.util.UUID.randomUUID().toString().take(8)
        activeSpanMap[spanId] = TraceSpan(spanId = spanId, name = name, parentSpanId = parentSpanId, attributes = attributes.toMutableMap())
        hubScope.launch { update { copy(activeSpanCount = activeSpanMap.size) } }
        return spanId
    }

    fun endSpan(spanId: String, success: Boolean = true, attributes: Map<String, String> = emptyMap()) {
        val span = activeSpanMap.remove(spanId) ?: return
        val endMs = System.currentTimeMillis()
        val completed = span.copy(endMs = endMs, durationMs = endMs - span.startMs, success = success, attributes = (span.attributes + attributes).toMutableMap())
        hubScope.launch {
            completedSpans.add(completed)
            if (completedSpans.size > MAX_COMPLETED_SPANS) completedSpans.removeAt(0)
            update { copy(activeSpanCount = activeSpanMap.size, completedSpans = this@AgentObservabilityHub.completedSpans.toList()) }
        }
    }

    fun resetSession() {
        hubScope.launch {
            sessionTurns = 0
            sessionTools = 0
            sessionTokens = 0
            toolCallCounts.clear()
            agentExecCounts.clear()
            agentErrCounts.clear()
            agentLastLatency.clear()
            errorRing.clear()
            activeSpanMap.clear()
            completedSpans.clear()
            _snapshot.value = ObservabilitySnapshot()
        }
    }

    private fun update(transform: ObservabilitySnapshot.() -> ObservabilitySnapshot) {
        _snapshot.value = _snapshot.value.transform()
    }

    data class ObservabilitySnapshot(
        val voiceState: VoicePipelineState = VoicePipelineState.IDLE,
        val lastSttLatencyMs: Long = 0L,
        val lastTtsFirstByteMs: Long = 0L,
        val perceivedLatencyMs: Long = 0L,
        val sessionInterruptions: Int = 0,
        val sessionVoiceErrors: Int = 0,
        val orchestratorState: OrchestratorStatus = OrchestratorStatus.IDLE,
        val orchestratorProgress: Int = 0,
        val agentExecutionCounts: Map<String, Int> = emptyMap(),
        val agentErrorCounts: Map<String, Int> = emptyMap(),
        val agentLastLatencyMs: Map<String, Long> = emptyMap(),
        val registeredAgents: List<SubAgentCapability> = emptyList(),
        val toolCallCounts: Map<String, Int> = emptyMap(),
        val sessionTotalToolCalls: Int = 0,
        val sessionTotalTurns: Int = 0,
        val sessionTokensConsumed: Int = 0,
        val episodicMemoryEntries: Int = 0,
        val semanticMemoryEntries: Int = 0,
        val longTermMemoryEntries: Int = 0,
        val durableTasksActive: Int = 0,
        val durableTasksCompleted: Int = 0,
        val durableTasksFailed: Int = 0,
        val durableTaskQueue: List<DurableTask> = emptyList(),
        val recentErrors: List<ErrorRecord> = emptyList(),
        val activeSpanCount: Int = 0,
        val completedSpans: List<TraceSpan> = emptyList(),
        val graphSnapshot: GraphSnapshot? = null
    )

    data class ErrorRecord(val agentId: String, val reason: String, val timestampMs: Long)
    enum class OrchestratorStatus { IDLE, RUNNING }
    data class TraceSpan(
        val spanId: String,
        val name: String,
        val parentSpanId: String? = null,
        val startMs: Long = System.currentTimeMillis(),
        val endMs: Long? = null,
        val durationMs: Long? = null,
        val success: Boolean = true,
        val attributes: MutableMap<String, String> = mutableMapOf()
    )
    data class GraphNodeView(
        val id: String,
        val description: String,
        val action: String,
        val status: String,
        val dependsOn: List<String>,
        val recovery: String
    )
}
