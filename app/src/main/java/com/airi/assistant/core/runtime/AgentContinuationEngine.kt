package com.airi.assistant.core.runtime

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/**
 * AgentContinuationEngine — rehydrates suspended [PersistentTaskSession]s
 * and resumes them from their last checkpoint.
 *
 * ── RESUMPTION PROTOCOL ───────────────────────────────────────────────────
 *
 *   1. Caller provides a [PersistentTaskSession] whose status is SUSPENDED
 *      (or PENDING if it never started).
 *   2. Engine locates the correct [SubAgent] via [SubAgentRegistry].
 *   3. Engine constructs a [SubAgentContext] that injects the checkpoint
 *      JSON as a dependency result named "checkpoint".
 *   4. The sub-agent's [execute] flow is re-invoked. The agent is expected
 *      to detect the "checkpoint" key and fast-forward past completed work.
 *   5. Engine updates [TaskCheckpointStore] on every Progress/Complete/Failed
 *      event emitted by the agent.
 *
 * ── ERROR HANDLING ────────────────────────────────────────────────────────
 *
 *   If the agent throws, the session is marked FAILED and the exception
 *   is rethrown wrapped in [ContinuationException]. The caller
 *   (AutonomousRuntimeManager) is responsible for deciding whether to
 *   retry or surface the error.
 */
class AgentContinuationEngine(
    private val checkpointStore: TaskCheckpointStore
) {

    private val TAG   = "AgentContinuationEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Resume a suspended session.
     *
     * Returns a [Flow] of [ResumeEvent]s that mirrors the sub-agent's
     * internal flow, enriched with session lifecycle events.
     */
    fun resume(session: PersistentTaskSession): Flow<ResumeEvent> = flow {
        LoggingService.info(TAG, "AIRI_PROOF CONTINUATION_RESUMED sessionId=${session.sessionId} agent=${session.agentId}")

        // Mark as RUNNING
        val running = session.withStatus(SessionStatus.RUNNING)
        checkpointStore.save(running)
        emit(ResumeEvent.SessionStarted(running))

        val agent = SubAgentRegistry.findById(session.agentId)
            ?: SubAgentRegistry.findById("research_agent")
            ?: run {
                val failed = session.withFailed("No agent found for id=${session.agentId}")
                checkpointStore.save(failed)
                emit(ResumeEvent.SessionFailed(failed, "No agent available"))
                return@flow
            }

        val ctx = SubAgentContext(
            sessionId = session.sessionId,
            userId    = "continuation_engine",
            worldState = emptyMap(),
            grantedPermissions = emptyList(),
            nestingDepth = 0,
            dependencyResults = buildMap {
                if (session.checkpointJson.isNotBlank()) {
                    put("checkpoint", session.checkpointJson)
                }
                put("step_index", session.stepIndex.toString())
                put("goal", session.goalText)
            }
        )

        var finalResult = ""
        var stepIndex   = session.stepIndex

        runCatching {
            withTimeout(RESUME_TIMEOUT_MS) {
                agent.execute(session.goalText, ctx).collect { event ->
                    when (event) {
                        is AgentEvent.Progress -> {
                            stepIndex++
                            val updated = session
                                .withStatus(SessionStatus.RUNNING)
                                .withCheckpoint(session.checkpointJson, stepIndex)
                            checkpointStore.save(updated)
                            emit(ResumeEvent.ProgressUpdate(updated, event.percentComplete, event.message))
                        }
                        is AgentEvent.PartialResult -> {
                            emit(ResumeEvent.PartialOutput(event.text))
                        }
                        is AgentEvent.Complete -> {
                            finalResult = event.result
                            val completed = session.withCompleted(finalResult)
                            checkpointStore.save(completed)
                            emit(ResumeEvent.SessionCompleted(completed))
                            Log.i(TAG, "AIRI_PROOF CONTINUATION_COMPLETE sessionId=${session.sessionId}")
                        }
                        is AgentEvent.Failed -> {
                            val failed = session.withFailed(event.reason)
                            checkpointStore.save(failed)
                            emit(ResumeEvent.SessionFailed(failed, event.reason))
                            Log.w(TAG, "AIRI_PROOF CONTINUATION_AGENT_FAILED sessionId=${session.sessionId} reason=${event.reason}")
                        }
                        else -> Unit
                    }
                }
            }
        }.onFailure { e ->
            val failed = session.withFailed(e.message ?: "unknown error")
            checkpointStore.save(failed)
            emit(ResumeEvent.SessionFailed(failed, e.message ?: "unknown"))
            Log.e(TAG, "AIRI_PROOF CONTINUATION_EXCEPTION sessionId=${session.sessionId} msg=${e.message}", e)
            throw ContinuationException("Continuation failed for ${session.sessionId}", e)
        }
    }

    /**
     * Recover all SUSPENDED sessions that were in-flight at last process kill.
     * Returns a list of resumed session IDs.
     */
    suspend fun recoverAll(): List<String> {
        val resumable = checkpointStore.resumable()
        Log.i(TAG, "AIRI_PROOF CONTINUATION_RECOVER_START count=${resumable.size}")
        return resumable.map { session ->
            Log.i(TAG, "AIRI_PROOF CONTINUATION_RECOVER_SESSION sessionId=${session.sessionId}")
            resume(session)
            session.sessionId
        }
    }

    companion object {
        private const val RESUME_TIMEOUT_MS = 10 * 60_000L
    }

    class ContinuationException(message: String, cause: Throwable? = null) :
        RuntimeException(message, cause)
}

/** Events emitted by [AgentContinuationEngine.resume]. */
sealed class ResumeEvent {
    data class SessionStarted  (val session: PersistentTaskSession) : ResumeEvent()
    data class ProgressUpdate  (val session: PersistentTaskSession, val percent: Int, val message: String) : ResumeEvent()
    data class PartialOutput   (val text: String) : ResumeEvent()
    data class SessionCompleted(val session: PersistentTaskSession) : ResumeEvent()
    data class SessionFailed   (val session: PersistentTaskSession, val reason: String) : ResumeEvent()
}
