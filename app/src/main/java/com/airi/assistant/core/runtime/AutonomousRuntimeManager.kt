package com.airi.assistant.core.runtime

import android.util.Log
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.core.UnifiedCognitiveLoop
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AutonomousRuntimeManager (ARM) — top-level session lifecycle orchestrator.
 *
 * ── RESPONSIBILITIES ──────────────────────────────────────────────────────
 *
 *   1. SESSION GATE      — creates, tracks, and tears down [PersistentTaskSession]s
 *   2. PERSISTENCE BRIDGE — delegates to [TaskCheckpointStore] for atomic writes
 *   3. CONTINUATION GATE  — on startup, hands resumable sessions to
 *                           [AgentContinuationEngine]
 *   4. LIFECYCLE SCOPE    — each session runs in an isolated SupervisorJob
 *                           child scope; cancelling one does not affect others
 *   5. OBSERVABILITY      — exposes [activeSessionCount] and [sessions] StateFlow
 *
 * ── USAGE ─────────────────────────────────────────────────────────────────
 *
 *     val sessionId = arm.startSession(
 *         goalText = "Research and summarise recent AI papers",
 *         agentId  = "research_agent"
 *     )
 *     arm.sessions.collect { sessions -> updateUI(sessions) }
 *
 * ── CRASH RECOVERY ────────────────────────────────────────────────────────
 *
 *   Call [recoverSuspendedSessions] from Application.onCreate() after
 *   ServiceLocator is ready. It finds all SUSPENDED sessions in
 *   [TaskCheckpointStore] and hands them to [AgentContinuationEngine].
 */
class AutonomousRuntimeManager(
    private val checkpointStore:       TaskCheckpointStore,
    private val continuationEngine:    AgentContinuationEngine,
    private val orchestrator:          ProductionAgentOrchestrator,
) {

    private val TAG   = "AutonomousRuntimeManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionScopes = ConcurrentHashMap<String, CoroutineScope>()

    private val _sessions = MutableStateFlow<List<PersistentTaskSession>>(emptyList())
    val sessions: StateFlow<List<PersistentTaskSession>> = _sessions.asStateFlow()

    val activeSessionCount: Int get() = _sessions.value.count { !it.isTerminal }

    init {
        // Populate StateFlow from store on construction
        refreshSessionList()
        LoggingService.info(TAG, "AIRI_PROOF ARM_INITIALIZED storedSessions=${checkpointStore.all().size}")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Create and start a new autonomous agent session.
     *
     * @param goalText   Natural-language goal.
     * @param agentId    Sub-agent responsible for execution.
     * @param totalSteps Planner estimate (0 = unknown).
     * @param metadata   Arbitrary key-value context attached to the session.
     * @return           The new [sessionId] (UUID).
     */
    fun startSession(
        goalText:   String,
        agentId:    String,
        totalSteps: Int                 = 0,
        metadata:   Map<String, String> = emptyMap()
    ): String {
        // Hard cap: prevent unbounded scope accumulation from runaway callers.
        require(sessionScopes.size < MAX_CONCURRENT_SESSIONS) {
            "ARM: concurrent session limit ($MAX_CONCURRENT_SESSIONS) reached; cancel an existing session first."
        }
        val sessionId = UUID.randomUUID().toString()
        val session   = PersistentTaskSession(
            sessionId  = sessionId,
            goalText   = goalText,
            agentId    = agentId,
            status     = SessionStatus.PENDING,
            totalSteps = totalSteps,
            metadata   = metadata
        )
        checkpointStore.save(session)
        refreshSessionList()

        Log.i(TAG, "AIRI_PROOF ARM_SESSION_STARTED sessionId=$sessionId agentId=$agentId goal='${goalText.take(80)}'")

        val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        sessionScopes[sessionId] = sessionScope

        sessionScope.launch {
            executeSession(session)
        }

        return sessionId
    }

    /**
     * Checkpoint a running session's progress.
     *
     * Should be called by the executing agent layer at each significant step.
     */
    fun checkpoint(sessionId: String, checkpointJson: String, stepIndex: Int) {
        checkpointStore.update(sessionId) { withCheckpoint(checkpointJson, stepIndex) }
        refreshSessionList()
        Log.d(TAG, "AIRI_PROOF ARM_CHECKPOINT sessionId=$sessionId step=$stepIndex")
    }

    /**
     * Suspend a running session (e.g. user backgrounded the app, resource unavailable).
     * The session can be resumed later via [recoverSuspendedSessions].
     */
    fun suspendSession(sessionId: String) {
        checkpointStore.update(sessionId) { withStatus(SessionStatus.SUSPENDED) }
        sessionScopes[sessionId]?.cancel()
        sessionScopes.remove(sessionId)
        refreshSessionList()
        Log.i(TAG, "AIRI_PROOF ARM_SESSION_SUSPENDED sessionId=$sessionId")
    }

    /** Cancel a session and mark it as CANCELLED. */
    fun cancelSession(sessionId: String) {
        checkpointStore.update(sessionId) { withCancelled() }
        sessionScopes[sessionId]?.cancel()
        sessionScopes.remove(sessionId)
        refreshSessionList()
        Log.i(TAG, "AIRI_PROOF ARM_SESSION_CANCELLED sessionId=$sessionId")
    }

    /** Get a single session by ID. */
    fun getSession(sessionId: String): PersistentTaskSession? = checkpointStore.load(sessionId)

    /**
     * On process restart: find all SUSPENDED sessions and resume them via
     * [AgentContinuationEngine]. Should be called once from Application.onCreate().
     */
    fun recoverSuspendedSessions() {
        val resumable = checkpointStore.resumable()
        Log.i(TAG, "AIRI_PROOF ARM_RECOVERY_START count=${resumable.size}")
        resumable.forEach { session ->
            val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
            sessionScopes[session.sessionId] = sessionScope
            sessionScope.launch {
                runCatching {
                    continuationEngine.resume(session).collect { event ->
                        when (event) {
                            is ResumeEvent.ProgressUpdate  -> refreshSessionList()
                            is ResumeEvent.SessionCompleted,
                            is ResumeEvent.SessionFailed   -> {
                                sessionScopes.remove(session.sessionId)
                                refreshSessionList()
                            }
                            else -> Unit
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "AIRI_PROOF ARM_RECOVERY_FAILED sessionId=${session.sessionId} msg=${e.message}", e)
                }
            }
        }
        refreshSessionList()
    }

    /** Remove terminal sessions older than 7 days. */
    fun pruneOldSessions() {
        val pruned = checkpointStore.prune()
        if (pruned > 0) refreshSessionList()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun executeSession(session: PersistentTaskSession) {
        val updated = session.withStatus(SessionStatus.RUNNING)
        checkpointStore.save(updated)
        refreshSessionList()

        runCatching {
            // Build a minimal SubAgentContext for autonomous sessions.
            // timeoutMs = -1L signals "no timeout" — ARM sessions run until completion.
            val ctx = SubAgentContext(
                sessionId = session.sessionId,
                userId    = session.metadata["userId"] ?: "arm_session",
                timeoutMs = -1L
            )
            val result = orchestrator.executeSingle(session.goalText, ctx)
            val summary = when (result) {
                is ProductionAgentOrchestrator.ExecutionResult.Success ->
                    result.finalResult.ifBlank {
                        "Completed ${result.taskResults.size} task(s) in ${result.durationMs}ms"
                    }
                is ProductionAgentOrchestrator.ExecutionResult.PartialFailure ->
                    "Partial failure: ${result.taskErrors.values.firstOrNull() ?: "unknown error"}"
            }
            val completed = checkpointStore.load(session.sessionId)?.withCompleted(summary)
                ?: session.withCompleted(summary)
            checkpointStore.save(completed)
            sessionScopes.remove(session.sessionId)
            refreshSessionList()
            Log.i(TAG, "AIRI_PROOF ARM_SESSION_COMPLETED sessionId=${session.sessionId} result='${summary.take(80)}'")
        }.onFailure { e ->
            val failed = session.withFailed(e.message ?: "orchestrator error")
            checkpointStore.save(failed)
            sessionScopes.remove(session.sessionId)
            refreshSessionList()
            Log.e(TAG, "AIRI_PROOF ARM_SESSION_FAILED sessionId=${session.sessionId} msg=${e.message}", e)
        }
    }

    private fun refreshSessionList() {
        _sessions.value = checkpointStore.all()
    }

    companion object {
        /** Maximum concurrent active sessions. Prevents unbounded coroutine scope accumulation. */
        const val MAX_CONCURRENT_SESSIONS = 20
    }
}
