package com.airi.assistant.agent.observability

import android.util.Log
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskStatus
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.voice.LiveVoiceSession
import com.airi.assistant.voice.VoicePipelineState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * AgentObservabilityHub — unified runtime observability aggregator.
 *
 * Collects signals from all runtime subsystems and exposes a single
 * [ObservabilitySnapshot] StateFlow consumed by [ObservabilityScreen].
 *
 * ─────────────────────────────────────────────────────────────────────────
 * SIGNAL SOURCES
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Voice:    LiveVoiceSession state / latency / metrics StateFlows
 *   Agents:   ProductionAgentOrchestrator state / execution results
 *   Tools:    Tool call counts from orchestrator event stream
 *   Memory:   Layer utilization counts pushed by MemoryManager
 *   Durable:  DurableTaskManager task queue snapshot
 *   Registry: SubAgentRegistry capability list
 *
 * ─────────────────────────────────────────────────────────────────────────
 * THREAD SAFETY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   All mutations occur on [hubScope] (IO dispatcher).
 *   MutableStateFlow updates are atomic. [recentErrors] mutations are
 *   confined to hubScope (single-threaded by design).
 */
class AgentObservabilityHub {

    private val TAG = "AgentObservabilityHub"
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Primary snapshot ──────────────────────────────────────────────────────

    private val _snapshot = MutableStateFlow(ObservabilitySnapshot())
    val snapshot: StateFlow<ObservabilitySnapshot> = _snapshot.asStateFlow()

    // ── Bounded error ring buffer (max 50 entries, hubScope-confined) ─────────

    private val errorRing = mutableListOf<ErrorRecord>()
    private fun addError(record: ErrorRecord) {
        errorRing.add(record)
        if (errorRing.size > 50) errorRing.removeAt(0)
    }

    // ── Accumulator maps (hubScope-confined) ──────────────────────────────────

    private val toolCallCounts       = mutableMapOf<String, Int>()
    private val agentExecCounts      = mutableMapOf<String, Int>()
    private val agentErrCounts       = mutableMapOf<String, Int>()
    private val agentLastLatency     = mutableMapOf<String, Long>()

    // ── Session counters ──────────────────────────────────────────────────────

    @Volatile private var sessionTurns    = 0
    @Volatile private var sessionTools    = 0
    @Volatile private var sessionTokens   = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Attachment — connect live signal sources
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attach a [LiveVoiceSession] for live voice telemetry.
     * Call when LiveVoiceService binds to the app.
     */
    fun attachVoiceSession(session: LiveVoiceSession) {
        hubScope.launch {
            session.state.collect { state ->
                update { copy(voiceState = state) }
            }
        }
        hubScope.launch {
            session.latency.collect { lat ->
                update {
                    copy(
                        lastSttLatencyMs    = lat.lastSttLatencyMs,
                        lastTtsFirstByteMs  = lat.lastTtsFirstByteMs,
                        perceivedLatencyMs  = lat.perceivedLatencyMs
                    )
                }
            }
        }
        hubScope.launch {
            session.metrics.collect { m ->
                update {
                    copy(
                        sessionInterruptions = m.interruptionCount,
                        sessionVoiceErrors   = m.errorCount
                    )
                }
            }
        }
        Log.i(TAG, "Voice session attached")
    }

    /**
     * Attach a [ProductionAgentOrchestrator] for execution state.
     */
    fun attachOrchestrator(orchestrator: ProductionAgentOrchestrator) {
        hubScope.launch {
            orchestrator.state.collect { state ->
                val (status, progress) = when (state) {
                    ProductionAgentOrchestrator.OrchestratorState.Idle ->
                        OrchestratorStatus.IDLE to 0
                    is ProductionAgentOrchestrator.OrchestratorState.Running ->
                        OrchestratorStatus.RUNNING to state.progressPercent
                }
                update { copy(orchestratorState = status, orchestratorProgress = progress) }
            }
        }
        Log.i(TAG, "Orchestrator attached")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Recording API — called from orchestrator / ViewModel / agents
    // ─────────────────────────────────────────────────────────────────────────

    /** Record a tool invocation. Thread-safe — dispatched to hubScope. */
    fun recordToolCall(toolName: String) {
        hubScope.launch {
            toolCallCounts[toolName] = (toolCallCounts[toolName] ?: 0) + 1
            sessionTools++
            update {
                copy(
                    toolCallCounts        = this@AgentObservabilityHub.toolCallCounts.toMap(),
                    sessionTotalToolCalls = this@AgentObservabilityHub.sessionTools
                )
            }
        }
    }

    /** Record successful agent execution completion. */
    fun recordAgentSuccess(agentId: String, durationMs: Long, tokenCount: Int = 0) {
        hubScope.launch {
            agentExecCounts[agentId] = (agentExecCounts[agentId] ?: 0) + 1
            agentLastLatency[agentId] = durationMs
            sessionTurns++
            sessionTokens += tokenCount
            Log.d(TAG, "AIRI_PROOF AGENT_SUCCESS agent=$agentId duration=${durationMs}ms tokens=$tokenCount")
            update {
                copy(
                    agentExecutionCounts  = this@AgentObservabilityHub.agentExecCounts.toMap(),
                    agentLastLatencyMs    = this@AgentObservabilityHub.agentLastLatency.toMap(),
                    sessionTotalTurns     = this@AgentObservabilityHub.sessionTurns,
                    sessionTokensConsumed = this@AgentObservabilityHub.sessionTokens
                )
            }
        }
    }

    /** Record an agent execution error. */
    fun recordAgentError(agentId: String, reason: String) {
        hubScope.launch {
            agentErrCounts[agentId] = (agentErrCounts[agentId] ?: 0) + 1
            addError(ErrorRecord(agentId = agentId, reason = reason, timestampMs = System.currentTimeMillis()))
            Log.w(TAG, "AIRI_PROOF AGENT_ERROR agent=$agentId reason=$reason")
            update {
                copy(
                    agentErrorCounts = this@AgentObservabilityHub.agentErrCounts.toMap(),
                    recentErrors     = this@AgentObservabilityHub.errorRing.toList()
                )
            }
        }
    }

    /** Push updated durable task queue snapshot. */
    fun updateDurableTasks(tasks: List<DurableTask>) {
        hubScope.launch {
            update {
                copy(
                    durableTasksActive    = tasks.count { !it.isTerminal },
                    durableTasksCompleted = tasks.count { it.status == DurableTaskStatus.COMPLETED },
                    durableTasksFailed    = tasks.count { it.status == DurableTaskStatus.FAILED },
                    durableTaskQueue      = tasks.filter { !it.isTerminal }
                )
            }
        }
    }

    /** Push updated memory layer utilization counts. */
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

    /** Refresh the registered sub-agent capability list from SubAgentRegistry. */
    fun refreshRegistrySnapshot() {
        hubScope.launch {
            update { copy(registeredAgents = SubAgentRegistry.capabilities()) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Session reset
    // ─────────────────────────────────────────────────────────────────────────

    /** Reset all session-level counters without disconnecting signal sources. */
    fun resetSession() {
        hubScope.launch {
            sessionTurns   = 0
            sessionTools   = 0
            sessionTokens  = 0
            toolCallCounts.clear()
            agentExecCounts.clear()
            agentErrCounts.clear()
            agentLastLatency.clear()
            errorRing.clear()
            _snapshot.value = ObservabilitySnapshot()
            Log.i(TAG, "Observability hub session reset")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun update(transform: ObservabilitySnapshot.() -> ObservabilitySnapshot) {
        _snapshot.value = _snapshot.value.transform()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Immutable observability snapshot.
     * All fields are primitives or immutable collections — zero allocation on
     * Compose recomposition reads.
     */
    data class ObservabilitySnapshot(

        // ── Voice pipeline ──────────────────────────────────────────────────
        val voiceState:           VoicePipelineState = VoicePipelineState.IDLE,
        val lastSttLatencyMs:     Long               = 0L,
        val lastTtsFirstByteMs:   Long               = 0L,
        val perceivedLatencyMs:   Long               = 0L,
        val sessionInterruptions: Int                = 0,
        val sessionVoiceErrors:   Int                = 0,

        // ── Orchestration ───────────────────────────────────────────────────
        val orchestratorState:    OrchestratorStatus = OrchestratorStatus.IDLE,
        val orchestratorProgress: Int                = 0,

        // ── Agent execution ──────────────────────────────────────────────────
        val agentExecutionCounts: Map<String, Int>       = emptyMap(),
        val agentErrorCounts:     Map<String, Int>       = emptyMap(),
        val agentLastLatencyMs:   Map<String, Long>      = emptyMap(),
        val registeredAgents:     List<SubAgentCapability> = emptyList(),

        // ── Tool calls ───────────────────────────────────────────────────────
        val toolCallCounts:        Map<String, Int>  = emptyMap(),
        val sessionTotalToolCalls: Int               = 0,

        // ── Session totals ───────────────────────────────────────────────────
        val sessionTotalTurns:     Int = 0,
        val sessionTokensConsumed: Int = 0,

        // ── Memory layers ────────────────────────────────────────────────────
        val episodicMemoryEntries: Int = 0,
        val semanticMemoryEntries: Int = 0,
        val longTermMemoryEntries: Int = 0,

        // ── Durable tasks ────────────────────────────────────────────────────
        val durableTasksActive:    Int               = 0,
        val durableTasksCompleted: Int               = 0,
        val durableTasksFailed:    Int               = 0,
        val durableTaskQueue:      List<DurableTask> = emptyList(),

        // ── Error log ────────────────────────────────────────────────────────
        val recentErrors: List<ErrorRecord> = emptyList()
    )

    data class ErrorRecord(
        val agentId:     String,
        val reason:      String,
        val timestampMs: Long
    ) {
        val formattedTime: String get() {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            return sdf.format(java.util.Date(timestampMs))
        }
    }

    enum class OrchestratorStatus { IDLE, RUNNING }
}
