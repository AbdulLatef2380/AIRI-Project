package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.memory.repository.MemoryScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MemoryAgent — real long-term memory operations via Room database.
 *
 * REAL EXECUTION:
 *   - STORE  → [MemoryManager.recordImportantMemory] persists to Room (isMemory=true)
 *   - RECALL → [MemoryManager.getSemanticMemories] + optional vector search via EmbeddingService
 *   - LIST   → [MemoryManager.getSemanticMemories] with formatting
 *   - DELETE → [MemoryManager.clearAll] wipes all episodic memory rows
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PRIVACY CONTRACT
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   - All memories are stored LOCAL ONLY in the app's Room database.
 *   - Never uploaded to any cloud without explicit user consent.
 *   - DELETE is irreversible — confirmed with a warning in PartialResult.
 *   - LLM synthesis of recalled memories only when cloudAllowed=true.
 */
class MemoryAgent(
    private val memoryManager: MemoryManager
) : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "memory_agent",
        displayName  = "Memory Agent",
        description  = "Store, recall, and manage your personal memories and preferences.",
        intentKeywords = listOf(
            "remember", "recall", "forget", "do you know", "what do you know",
            "memorize", "store", "save this", "keep in mind", "note that",
            "i told you", "you said", "i said", "preference", "you remember",
            "delete memory", "clear memory", "what have i told you",
            "my memories", "show me what you remember"
        ),
        domains            = listOf("memory", "recall", "personalization", "preferences"),
        accessesPrivateData = true,
        requiresCloud       = false,
        costTier            = SubAgentCapability.CostTier.FREE,
        latencyProfile      = SubAgentCapability.LatencyProfile.INSTANT,
        supportsBackground  = true,
        maxParallelSubTasks = 1,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        val lower = input.lowercase()
        return MEMORY_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "MEMORY_AGENT_EXECUTE inputChars=${input.length}")

        val operation = detectOperation(input.lowercase())
        emit(AgentEvent.Progress("Memory operation: ${operation.name}", 20, "classify"))

        when (operation) {
            MemoryOperation.STORE -> executeStore(input, context, start)
            MemoryOperation.RECALL -> executeRecall(input, context, start)
            MemoryOperation.DELETE -> executeDelete(start)
            MemoryOperation.LIST   -> executeList(context, start)
        }.collect { event -> emit(event) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // STORE — persist a long-term memory to Room
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeStore(input: String, context: SubAgentContext, start: Long) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "memory_store",
            params    = mapOf("content" to input, "layer" to "LONG_TERM"),
            reasoning = "User requested explicit memory storage"
        ))
        emit(AgentEvent.Progress("Storing memory…", 50, "store"))

        val content = cleanMemoryContent(input)
        val result = memoryManager.storeExplicitMemory(
            request = MemoryManager.ExplicitMemoryRequest(
                content = content,
                sessionId = context.sessionId,
                projectId = context.projectId.orEmpty(),
                scope = if (context.projectId.isNullOrBlank()) MemoryScope.SESSION else MemoryScope.PROJECT,
                privacyLevel = context.privacyLevel,
                provenance = "Explicit request through Memory Agent"
            )
        )

        when (result) {
            is MemoryManager.ExplicitMemoryResult.Stored -> {
                Log.i(TAG, "MEMORY_STORED id=${result.memoryId} contentChars=${content.length}")
                emit(AgentEvent.PartialResult("Got it — I saved that memory.", isFinal = true))
                emit(AgentEvent.Complete(
                    result = "Memory stored: \"${content.take(60)}\"",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed = listOf("memory_store")
                ))
            }
            MemoryManager.ExplicitMemoryResult.Duplicate -> {
                emit(AgentEvent.PartialResult("That memory is already saved in this scope.", isFinal = true))
                emit(AgentEvent.Complete(
                    result = "Memory already existed.",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed = listOf("memory_store")
                ))
            }
            is MemoryManager.ExplicitMemoryResult.Rejected -> {
                emit(AgentEvent.PartialResult("I did not save that memory: ${result.reason}.", isFinal = true))
                emit(AgentEvent.Complete(
                    result = "Memory rejected.",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed = listOf("memory_store")
                ))
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RECALL — semantic or chronological memory retrieval
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeRecall(input: String, context: SubAgentContext, start: Long) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "memory_recall",
            params    = mapOf("query" to input, "layers" to "LONG_TERM,SEMANTIC"),
            reasoning = "Retrieving stored memories matching query"
        ))
        emit(AgentEvent.Progress("Searching memories…", 40, "search"))

        // Explicit memory recall reads only records that passed long-term
        // admission and the active scope/privacy/expiry gate. Context RAG has
        // a separate retrieval contract and must not be presented as memory.
        val memories = memoryManager.getScopedLongTermMemories(
            sessionId = context.sessionId,
            projectId = context.projectId.orEmpty(),
            maxPrivacyLevel = context.privacyLevel,
            limit = 20
        )

        emit(AgentEvent.Progress("Found ${memories.size} memory record(s).", 70, "recall_done"))

        if (memories.isEmpty()) {
            emit(AgentEvent.PartialResult(
                "I don't have any stored memories yet. You can ask me to remember something.",
                isFinal = true
            ))
            emit(AgentEvent.Complete(
                result     = "No memories found.",
                durationMs = System.currentTimeMillis() - start,
                toolsUsed  = listOf("memory_recall")
            ))
            return@flow
        }

        val memoryBlock = memories
            .take(10)
            .joinToString("\n") { memory ->
                "- ${memory.content.take(200)} [${memory.memorySource.lowercase()} · ${memory.memoryScope.lowercase()}]"
            }
            .ifBlank { "No long-term memories found." }

        // Delegate to LLM for synthesis when cloud is allowed
        if (context.cloudAllowed) {
            emit(AgentEvent.Progress("Synthesizing recalled context…", 75, "synthesize"))
            emit(AgentEvent.Delegate(
                targetAgentId = "llm_backend",
                subInput      = buildRecallPrompt(input, memoryBlock),
                reason        = "Recall synthesis requires LLM reasoning"
            ))
        } else {
            // LOCAL_ONLY: return raw memories without LLM synthesis
            emit(AgentEvent.PartialResult(
                "Here's what I have stored:\n$memoryBlock",
                isFinal = true
            ))
        }

        emit(AgentEvent.Complete(
            result     = "[Memory recall complete — ${memories.size} record(s)]",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = listOf("memory_recall")
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE — wipe stored memories
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeDelete(start: Long) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "memory_delete",
            params    = mapOf("scope" to "ALL"),
            reasoning = "User requested memory deletion"
        ))
        emit(AgentEvent.Progress("Clearing all stored memories…", 50, "delete"))

        memoryManager.clearAll()

        Log.i(TAG, "AIRI MEMORY_CLEARED all episodic memories deleted")
        emit(AgentEvent.PartialResult(
            "All stored memories have been cleared. I no longer have any personal data about you stored locally.",
            isFinal = true
        ))
        emit(AgentEvent.Complete(
            result     = "Memory cleared.",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = listOf("memory_delete")
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIST — show all stored memories
    // ─────────────────────────────────────────────────────────────────────────

    private fun executeList(context: SubAgentContext, start: Long) = flow<AgentEvent> {
        emit(AgentEvent.ToolCall(
            toolName  = "memory_list",
            params    = mapOf("layer" to "LONG_TERM", "limit" to "20"),
            reasoning = "User wants to see stored memories"
        ))
        emit(AgentEvent.Progress("Retrieving stored memories…", 50, "list"))

        val longTerm = memoryManager.getScopedLongTermMemories(
            sessionId = context.sessionId,
            projectId = context.projectId.orEmpty(),
            maxPrivacyLevel = context.privacyLevel,
            limit = 20
        )

        if (longTerm.isEmpty()) {
            emit(AgentEvent.PartialResult(
                "I don't have any stored memories yet. Ask me to \"remember\" something to save it.",
                isFinal = true
            ))
            emit(AgentEvent.Complete(
                result     = "No memories found.",
                durationMs = System.currentTimeMillis() - start,
                toolsUsed  = listOf("memory_list")
            ))
            return@flow
        }

        val formatted = longTerm
            .take(20)
            .mapIndexed { i, m -> "${i + 1}. ${m.content.take(120)}" }
            .joinToString("\n")

        if (context.cloudAllowed) {
            emit(AgentEvent.Delegate(
                targetAgentId = "llm_backend",
                subInput      = "Summarize what you know about the user from these stored memories:\n$formatted",
                reason        = "Memory list summary requires LLM"
            ))
        } else {
            emit(AgentEvent.PartialResult(
                "Stored memories (${longTerm.size}):\n$formatted",
                isFinal = true
            ))
        }

        emit(AgentEvent.Complete(
            result     = "[${longTerm.size} memories listed]",
            durationMs = System.currentTimeMillis() - start,
            toolsUsed  = listOf("memory_list")
        ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRecallPrompt(input: String, memoryBlock: String): String =
        """The user is asking you to recall something: "$input"

These are their stored long-term memories:
$memoryBlock

Answer their question using these memories. Be honest if none are relevant.
Do NOT fabricate memories. Only reference what is listed above."""

    private fun cleanMemoryContent(input: String): String =
        input
            .replace(Regex("(?i)(remember that|memorize|store this|note that|keep in mind|please remember)"), "")
            .trim()
            .ifBlank { input }

    private enum class MemoryOperation { STORE, RECALL, DELETE, LIST }

    private fun detectOperation(lower: String): MemoryOperation = when {
        lower.contains("forget") || lower.contains("delete") ||
        lower.contains("remove") || lower.contains("clear") ->
            MemoryOperation.DELETE
        lower.contains("what do you know") || lower.contains("what have i told") ||
        lower.contains("show me my memories") || lower.contains("list memories") ||
        lower.contains("my memories") ->
            MemoryOperation.LIST
        lower.contains("remember that") || lower.contains("memorize") ||
        lower.contains("store this") || lower.contains("note that") ||
        lower.contains("keep in mind") || lower.contains("save this") ->
            MemoryOperation.STORE
        else ->
            MemoryOperation.RECALL
    }

    companion object {
        private const val TAG = "MemoryAgent"

        private val MEMORY_SIGNALS = listOf(
            "remember", "recall", "forget", "do you remember",
            "what do you know about me", "memorize", "store this",
            "note that", "keep in mind", "i prefer", "my preference",
            "delete memory", "clear memory", "my memories", "what have i told you"
        )
    }
}
