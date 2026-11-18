package com.airi.assistant.agent.loop

import android.content.Context
import com.airi.assistant.execution.AgentOrchestrator
import com.airi.assistant.execution.ChatExecutionIdentityContract
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.ui.generative.GenerativeUiCompileResult
import com.airi.assistant.ui.generative.GenerativeUiCompiler
import com.airi.assistant.ui.generative.GenerativeUiLimits
import com.airi.assistant.ui.generative.GenerativeUiRejectReason

/**
 * Pure decision and wording logic for the single "repair" retry of a rejected
 * airi-ui block. It has no Android or network dependency, so it is fully
 * unit-testable; [GenerativeUiRepairRunner] performs the one model call.
 */
object GenerativeUiRepairPolicy {
    /** Upper bound of the rejected answer echoed back to the model. */
    const val MAX_ECHOED_ANSWER_CHARS = 12_000

    /** Reasons a model can plausibly fix by emitting the block again. */
    private val repairable = setOf(
        GenerativeUiRejectReason.UNCLOSED_FENCE,
        GenerativeUiRejectReason.BAD_JSON,
        GenerativeUiRejectReason.BAD_VERSION,
        GenerativeUiRejectReason.UNKNOWN_KEY,
        GenerativeUiRejectReason.UNKNOWN_BLOCK,
        GenerativeUiRejectReason.BAD_ID,
        GenerativeUiRejectReason.DUPLICATE_ID,
        GenerativeUiRejectReason.LIMIT_EXCEEDED,
        GenerativeUiRejectReason.INVALID_SCHEMA,
    )

    /**
     * NO_FENCE means there was no candidate at all, and OVERSIZE means the answer
     * itself is too large to echo back, so neither is retried.
     */
    fun isRepairable(reason: GenerativeUiRejectReason): Boolean = reason in repairable

    /**
     * Decides whether a retry is allowed for [answer]. Only a finished answer that
     * really carried an airi-ui candidate, from a non-local provider, in a run that
     * invoked no tools (so nothing can be executed twice), qualifies.
     */
    fun reasonToRepair(answer: String, providerId: String, toolsInvoked: Int): GenerativeUiRejectReason? {
        if (toolsInvoked > 0) return null
        if (providerId.isBlank() || providerId.equals("local", ignoreCase = true)) return null
        if (answer.length > GenerativeUiRepairPolicy.MAX_ECHOED_ANSWER_CHARS) return null
        val rejected = GenerativeUiCompiler.compileDetailed(answer) as? GenerativeUiCompileResult.Rejected
            ?: return null
        return rejected.reason.takeIf { isRepairable(it) }
    }

    /** What the model is told to change. Wording depends only on the typed reason. */
    fun hint(reason: GenerativeUiRejectReason): String {
        val l = GenerativeUiLimits
        return when (reason) {
            GenerativeUiRejectReason.UNCLOSED_FENCE -> "the airi-ui block was never closed; end it with a closing ``` fence"
            GenerativeUiRejectReason.BAD_JSON -> "the block was not valid JSON; use double-quoted keys and strings and no trailing commas"
            GenerativeUiRejectReason.BAD_VERSION -> "`version` must be the number 1"
            GenerativeUiRejectReason.UNKNOWN_KEY -> "an object contained a key that is not part of the contract; remove it"
            GenerativeUiRejectReason.UNKNOWN_BLOCK -> "a block `type` is not supported; use only stat, bar_chart, diagram, route, table, checklist, form or actions"
            GenerativeUiRejectReason.BAD_ID -> "an id must match [A-Za-z0-9_-]+ and be at most ${l.MAX_ID_CHARS} characters"
            GenerativeUiRejectReason.DUPLICATE_ID -> "every id in the document must be unique, including nested ids and a form's actionId"
            GenerativeUiRejectReason.LIMIT_EXCEEDED -> "a size limit was exceeded; stay within ${l.MAX_BLOCKS} blocks, ${l.MAX_ITEMS} list entries, " +
                "tables of ${l.MIN_TABLE_COLUMNS}-${l.MAX_TABLE_COLUMNS} columns and up to ${l.MAX_TABLE_ROWS} rows, " +
                "${l.MIN_ROUTE_STOPS}-${l.MAX_ROUTE_STOPS} route stops, ${l.MIN_FORM_FIELDS}-${l.MAX_FORM_FIELDS} form fields"
            GenerativeUiRejectReason.INVALID_SCHEMA -> "a field had the wrong type, was missing, or a table row did not match its headers"
            GenerativeUiRejectReason.NO_FENCE,
            GenerativeUiRejectReason.OVERSIZE -> "the block was not usable"
        }
    }

    fun instruction(reason: GenerativeUiRejectReason): String =
        "Your previous reply contained an `airi-ui` block that AIRI could not render: ${hint(reason)}. " +
            "Write the same reply again with a corrected block that follows the airi-ui contract exactly. " +
            "If the block is not essential, answer in plain text with no `airi-ui` block. " +
            "Do not mention this correction or the error."

    /**
     * Accepts a repaired reply only if it is blank-free and either renders as a
     * valid block or is plain text without any airi-ui remnant. Otherwise the
     * original answer is kept, so a retry can never make things worse.
     */
    fun acceptRepaired(repaired: String): String? {
        val text = repaired.trim()
        if (text.isBlank()) return null
        return when (val result = GenerativeUiCompiler.compileDetailed(text)) {
            is GenerativeUiCompileResult.Accepted -> text
            is GenerativeUiCompileResult.Rejected ->
                text.takeIf {
                    result.reason == GenerativeUiRejectReason.NO_FENCE &&
                        !it.contains("```airi-ui", ignoreCase = true)
                }
        }
    }
}

/** Performs the one silent, tool-less model call that repairs a rejected block. */
class GenerativeUiRepairRunner(
    private val orchestrator: AgentOrchestrator,
    private val appContext: Context,
) {
    data class Input(
        val userInput: String,
        val rejectedAnswer: String,
        val reason: GenerativeUiRejectReason,
        val systemPrompt: String,
        val priorConversation: List<ExecutionRequest.ConversationTurn>,
        val providerId: String,
        val modelId: String,
        val sessionId: String,
        val executionId: String,
    )

    /** Returns an accepted repaired reply, or null when nothing better than the original exists. */
    suspend fun repair(input: Input): String? {
        val buffer = StringBuilder()
        var failed = false
        val history = input.priorConversation + listOf(
            ExecutionRequest.ConversationTurn(role = "user", content = input.userInput),
            ExecutionRequest.ConversationTurn(
                role = "assistant",
                content = input.rejectedAnswer.take(GenerativeUiRepairPolicy.MAX_ECHOED_ANSWER_CHARS),
            ),
        )
        val prompt = GenerativeUiRepairPolicy.instruction(input.reason)
        val estimatedTokens = (input.systemPrompt.length + history.sumOf { it.content.length } + prompt.length) / 4
        orchestrator.executeStream(
            request = ExecutionRequest(
                prompt = prompt,
                localPrompt = prompt,
                systemPrompt = input.systemPrompt,
                maxTokens = REPAIR_MAX_TOKENS,
                temperature = 0.1f,
                requiresStreaming = true,
                estimatedPromptTokens = estimatedTokens,
                sessionTag = "genui_repair",
                requestedProviderId = input.providerId,
                requestedModelId = input.modelId,
                conversationHistory = history,
                identity = ChatExecutionIdentityContract.create(input.sessionId, input.executionId),
            ),
            context = appContext,
            onToken = { token -> buffer.append(token) },
            onComplete = { text, _, _, _ ->
                if (text.isNotBlank()) {
                    buffer.clear()
                    buffer.append(text)
                }
            },
            onError = { _, _ -> failed = true },
        )
        if (failed) return null
        return GenerativeUiRepairPolicy.acceptRepaired(buffer.toString())
    }

    private companion object {
        const val REPAIR_MAX_TOKENS = 3_000
    }
}
