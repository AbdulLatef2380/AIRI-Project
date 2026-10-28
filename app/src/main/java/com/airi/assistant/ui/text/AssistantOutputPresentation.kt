package com.airi.assistant.ui.text

/** One tool result retained for presentation separately from the assistant's final prose. */
data class AssistantToolOutput(
    val id: String,
    val toolName: String,
    val step: Int,
    val text: String,
    val truncated: Boolean = false,
    val actions: Set<ContentUnitAction> = setOf(ContentUnitAction.EXPAND),
)

/**
 * Explicit provenance boundary for an assistant turn. Tool results are never
 * appended to [agentOutput]; the UI can disclose them independently on demand.
 */
data class AssistantOutputPresentation(
    val agentOutput: String,
    val toolOutputs: List<AssistantToolOutput> = emptyList(),
)
