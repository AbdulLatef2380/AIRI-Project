package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MemoryAgent — recall, store, and manage long-term user memories.
 *
 * Handles explicit memory operations from the user:
 *   "Remember that I prefer…"
 *   "What do you know about me?"
 *   "Forget what I told you about…"
 *   "Do you remember when I said…?"
 *
 * Accesses MemoryManager (Room DB) for long-term and semantic recall.
 * Never uploads private memories to cloud without explicit consent.
 */
class MemoryAgent : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "memory_agent",
        displayName  = "Memory Agent",
        description  = "Store, recall, and manage your personal memories and preferences.",
        intentKeywords = listOf(
            "remember", "recall", "forget", "do you know", "what do you know",
            "memorize", "store", "save this", "keep in mind", "note that",
            "i told you", "you said", "i said", "preference", "you remember",
            "delete memory", "clear memory", "what have i told you"
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
        val memorySignals = listOf(
            "remember", "recall", "forget", "do you remember",
            "what do you know about me", "memorize", "store this",
            "note that", "keep in mind", "i prefer", "my preference",
            "delete memory", "clear memory"
        )
        return memorySignals.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "MemoryAgent.execute input='${input.take(80)}'")

        val operation = detectOperation(input.lowercase())
        emit(AgentEvent.Progress("Memory operation: ${operation.name}", 20, "classify"))

        when (operation) {
            MemoryOperation.STORE -> {
                emit(AgentEvent.Progress("Storing memory…", 50, "store"))
                emit(AgentEvent.ToolCall(
                    toolName  = "memory_store",
                    params    = mapOf("content" to input, "layer" to "LONG_TERM"),
                    reasoning = "User requested explicit memory storage"
                ))
                emit(AgentEvent.PartialResult("Got it! I'll remember that."))
                emit(AgentEvent.Complete(
                    result     = "Memory stored successfully.",
                    durationMs = System.currentTimeMillis() - start
                ))
            }

            MemoryOperation.RECALL -> {
                emit(AgentEvent.Progress("Searching memories…", 40, "search"))
                emit(AgentEvent.ToolCall(
                    toolName  = "memory_recall",
                    params    = mapOf("query" to input, "layers" to "LONG_TERM,SEMANTIC"),
                    reasoning = "Semantic memory recall"
                ))
                emit(AgentEvent.Progress("Synthesizing recalled context…", 70, "synthesize"))
                emit(AgentEvent.Delegate(
                    targetAgentId = "llm_backend",
                    subInput      = buildRecallPrompt(input, context),
                    reason        = "Recall synthesis requires LLM"
                ))
                emit(AgentEvent.Complete(
                    result     = "[Memory recall delegated to LLM with context]",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed  = listOf("memory_recall")
                ))
            }

            MemoryOperation.DELETE -> {
                emit(AgentEvent.Progress("Locating memory to delete…", 40, "locate"))
                emit(AgentEvent.ToolCall(
                    toolName  = "memory_delete",
                    params    = mapOf("query" to input),
                    reasoning = "User requested memory deletion"
                ))
                emit(AgentEvent.PartialResult("Memory removed. I no longer have that stored."))
                emit(AgentEvent.Complete(
                    result     = "Memory deleted.",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed  = listOf("memory_delete")
                ))
            }

            MemoryOperation.LIST -> {
                emit(AgentEvent.Progress("Retrieving memory summary…", 50, "list"))
                emit(AgentEvent.ToolCall(
                    toolName  = "memory_list",
                    params    = mapOf("layer" to "LONG_TERM", "limit" to "20"),
                    reasoning = "User wants to see what is remembered"
                ))
                emit(AgentEvent.Delegate(
                    targetAgentId = "llm_backend",
                    subInput      = "Summarize what you know about the user from their stored memories.",
                    reason        = "Memory summary requires LLM"
                ))
                emit(AgentEvent.Complete(
                    result     = "[Memory list delegated to LLM]",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed  = listOf("memory_list")
                ))
            }
        }
    }

    private fun buildRecallPrompt(input: String, context: SubAgentContext): String =
        """The user is asking you to recall something: "$input"
           
Please search your long-term memory for relevant information about this.
Be honest if you don't have a stored memory about it.
Do not fabricate memories."""

    private enum class MemoryOperation { STORE, RECALL, DELETE, LIST }

    private fun detectOperation(lower: String): MemoryOperation = when {
        lower.contains("forget") || lower.contains("delete") || lower.contains("remove") ->
            MemoryOperation.DELETE
        lower.contains("what do you know") || lower.contains("what have i told") ||
        lower.contains("show me my memories") || lower.contains("list memories") ->
            MemoryOperation.LIST
        lower.contains("remember that") || lower.contains("memorize") ||
        lower.contains("store this") || lower.contains("note that") ||
        lower.contains("keep in mind") || lower.contains("i prefer") ->
            MemoryOperation.STORE
        else -> MemoryOperation.RECALL
    }

    companion object { private const val TAG = "MemoryAgent" }
}
