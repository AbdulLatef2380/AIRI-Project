package com.airi.assistant.domain.intent

/** Shared semantic projection consumed by routing, admission and diagnostics. */
data class ResolvedIntent(
    val kind: IntentKind,
    val capabilities: List<ResolvedCapability> = emptyList(),
    val targets: List<IntentTarget> = emptyList(),
    val confidence: ChatIntent.Confidence,
    val risk: ChatIntent.Risk,
    val missingSlots: List<IntentSlot> = emptyList(),
    val rationale: List<String> = emptyList(),
) {
    enum class IntentKind { CONVERSATION, QUESTION, CREATIVE, CONNECTOR_ACTION, DEVICE_ACTION, CLARIFICATION }
    data class ResolvedCapability(val family: String, val operation: String, val risk: ChatIntent.Risk)
    data class IntentTarget(val type: String, val value: String)
    data class IntentSlot(val name: String)

    companion object {
        fun from(input: String, intent: ChatIntent): ResolvedIntent {
            val capability = intent.canonicalCapability.orEmpty()
            val kind = when {
                intent.kind == ChatIntent.Kind.CONVERSATION -> IntentKind.CONVERSATION
                intent.kind == ChatIntent.Kind.CREATIVE -> IntentKind.CREATIVE
                intent.kind == ChatIntent.Kind.CLARIFICATION -> IntentKind.CLARIFICATION
                capability.startsWith("connector") -> IntentKind.CONNECTOR_ACTION
                capability == "device_action" || capability == "open_settings" -> IntentKind.DEVICE_ACTION
                intent.kind == ChatIntent.Kind.QUESTION -> IntentKind.QUESTION
                else -> IntentKind.QUESTION
            }
            val targets = intent.typedSlots
                .filterValues { it.type == ChatIntent.TypedSlot.SlotType.TARGET }
                .map { IntentTarget(it.key, it.value.value) }
            val missing = if (kind == IntentKind.CONNECTOR_ACTION && targets.isEmpty()) {
                listOf(IntentSlot("connector_target"))
            } else emptyList()
            return ResolvedIntent(
                kind = kind,
                capabilities = capability.takeIf { it.isNotBlank() }?.let {
                    listOf(ResolvedCapability(it.substringBefore('_'), it, intent.risk))
                }.orEmpty(),
                targets = targets,
                confidence = intent.confidence,
                risk = intent.risk,
                missingSlots = missing,
                rationale = listOf("canonical=${intent.rationale}", "input_chars=${input.length}"),
            )
        }
    }
}

val ChatIntent.resolvedIntent: ResolvedIntent
    get() = ResolvedIntent.from("", this)
