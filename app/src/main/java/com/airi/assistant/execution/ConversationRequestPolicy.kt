package com.airi.assistant.execution

/** Canonical request split shared by AgentLoop and stateless cloud adapters. */
data class ConversationRequestProjection(
    val prompt: String,
    val conversationHistory: List<ExecutionRequest.ConversationTurn>,
)

object ConversationRequestPolicy {
    /**
     * A current prompt is represented either in [prompt] or in history, never both.
     * When there is no new user turn (for example, after a tool result), keep the
     * complete history and leave prompt blank so adapters do not append an empty turn.
     */
    fun project(
        turns: List<ExecutionRequest.ConversationTurn>,
        currentPromptText: String?,
    ): ConversationRequestProjection {
        if (currentPromptText == null) {
            return ConversationRequestProjection(prompt = "", conversationHistory = turns)
        }
        val last = turns.lastOrNull()
        require(last?.role == "user" && last.content == currentPromptText) {
            "The current prompt must be the final user turn in conversation history."
        }
        return ConversationRequestProjection(
            prompt = currentPromptText,
            conversationHistory = turns.dropLast(1),
        )
    }
}
