package com.airi.assistant.agent.memory

import android.util.Log
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.memory.rag.RagRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AgentMemoryBridge — typed access layer between the agent execution pipeline
 * and the memory/RAG subsystem.
 *
 * ── PURPOSE ──────────────────────────────────────────────────────────────────
 *
 * [MemoryManager] is a general-purpose store used by chat. The agent pipeline
 * has stricter requirements:
 *
 *   1. EPISODIC RECALL  — retrieve recent conversation context relevant to a goal.
 *   2. PROCEDURAL STORE — persist agent-generated actions/observations.
 *   3. SEMANTIC SEARCH  — query past successful plans for the same goal class.
 *   4. GOAL MEMORY      — record goal outcomes for future self-improvement.
 *
 * This class provides a clean API for those use cases without coupling agents
 * to the raw Room DAO or EmbeddingService internals.
 *
 * ── THREADING ────────────────────────────────────────────────────────────────
 *
 * All suspend functions switch to [Dispatchers.IO] before accessing the
 * database. The bridge is safe to call from any coroutine scope.
 */
class AgentMemoryBridge(
    private val memoryManager: MemoryManager,
    private val ragRetriever:  RagRetriever,
) {

    private val TAG = "AgentMemoryBridge"

    // ── Types ─────────────────────────────────────────────────────────────────

    data class MemoryChunk(
        val role:        String,
        val content:     String,
        val relevance:   Float = 1.0f,
        val timestampMs: Long  = System.currentTimeMillis(),
    )

    data class GoalMemoryEntry(
        val goalDescription: String,
        val outcome:         String,
        val successfulSteps: List<String>,
        val agentId:         String,
        val timestampMs:     Long = System.currentTimeMillis(),
    )

    // ── Recall API ────────────────────────────────────────────────────────────

    /**
     * Retrieve the most relevant memory chunks for [query].
     * Uses the RAG retriever for semantic search over past messages.
     */
    suspend fun recall(query: String, limit: Int = 5): List<MemoryChunk> =
        withContext(Dispatchers.IO) {
            runCatching {
                val results = ragRetriever.retrieveRelevant(query, limit)
                results.map { MemoryChunk(role = it.role, content = it.content) }
            }.getOrElse {
                Log.w(TAG, "recall failed: ${it.message}")
                emptyList()
            }
        }

    /**
     * Retrieve recent conversation turns for session context injection.
     * @param sessionId   The current chat session identifier.
     * @param turnLimit   How many recent turns to include.
     */
    suspend fun recentTurns(sessionId: String, turnLimit: Int = 6): List<MemoryChunk> =
        withContext(Dispatchers.IO) {
            runCatching {
                memoryManager.getRecentMessages(sessionId, turnLimit)
                    .map { MemoryChunk(role = it.role, content = it.content) }
            }.getOrElse {
                Log.w(TAG, "recentTurns failed: ${it.message}")
                emptyList()
            }
        }

    /**
     * Persist an agent action/observation pair to episodic memory.
     *
     * @param sessionId   Current session.
     * @param action      What the agent did.
     * @param observation What was observed / returned.
     */
    suspend fun storeAgentObservation(
        sessionId:   String,
        action:      String,
        observation: String,
    ) = withContext(Dispatchers.IO) {
        runCatching {
            memoryManager.recordChatMessage(sessionId, "agent_action", action)
            memoryManager.recordChatMessage(sessionId, "agent_observation", observation)
            Log.d(TAG, "Stored agent observation: action='${action.take(60)}'")
        }.onFailure { Log.w(TAG, "storeAgentObservation failed: ${it.message}") }
    }

    /**
     * Record a completed goal outcome for future self-improvement.
     */
    suspend fun recordGoalOutcome(entry: GoalMemoryEntry) = withContext(Dispatchers.IO) {
        runCatching {
            val summary = buildString {
                append("[GOAL_OUTCOME] ${entry.goalDescription}")
                append(" | outcome=${entry.outcome}")
                append(" | agent=${entry.agentId}")
                if (entry.successfulSteps.isNotEmpty()) {
                    append(" | steps: ${entry.successfulSteps.joinToString(", ")}")
                }
            }
            memoryManager.recordInteraction("system", summary)
            Log.i(TAG, "Recorded goal outcome: '${entry.goalDescription.take(60)}' outcome=${entry.outcome}")
        }.onFailure { Log.w(TAG, "recordGoalOutcome failed: ${it.message}") }
    }

    /**
     * Build a context string suitable for injecting into an LLM prompt.
     * Combines semantic recall + recent turns into a single formatted block.
     */
    suspend fun buildAgentContext(
        query:     String,
        sessionId: String,
        maxTokens: Int = 800,
    ): String = withContext(Dispatchers.IO) {
        val recalled = runCatching { recall(query, limit = 3) }.getOrDefault(emptyList())
        val recent   = runCatching { recentTurns(sessionId, turnLimit = 4) }.getOrDefault(emptyList())

        val sb = StringBuilder()
        if (recalled.isNotEmpty()) {
            sb.appendLine("## Relevant memories:")
            recalled.forEach { sb.appendLine("- ${it.content.take(200)}") }
        }
        if (recent.isNotEmpty()) {
            sb.appendLine("## Recent conversation:")
            recent.takeLast(4).forEach { sb.appendLine("[${it.role}]: ${it.content.take(200)}") }
        }

        val result = sb.toString().take(maxTokens * 4)
        result
    }
}
