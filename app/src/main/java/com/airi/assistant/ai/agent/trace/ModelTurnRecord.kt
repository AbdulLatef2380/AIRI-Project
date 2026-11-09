package com.airi.assistant.ai.agent.trace

/**
 * Part 5 §3.4: what one model call of an agent run did. [AgentLoop][com.airi.assistant.agent.loop.AgentLoop]
 * records one per provider request (not for tool calls replayed from its own queue), and the
 * trace mapper turns each into an [AgentStepType.MODEL_TURN] step so it travels through the same
 * trace pipeline (live bridge, final record, Room) as every other step.
 *
 * All text fields are short identifiers (never prompts, replies or credentials). Token counts are
 * estimates: the provider layer does not report usage per turn.
 */
data class ModelTurnRecord(
    /** 1-based position among the run's model calls. */
    val turn: Int,
    val timestampMs: Long,
    /** Request dispatch to the end of the streamed reply. */
    val durationMs: Long,
    val providerId: String,
    val modelId: String,
    val requestedProviderId: String = "",
    val requestedModelId: String = "",
    /** "auto" or "required": what the loop asked of the provider for this turn. */
    val toolChoice: String = TOOL_CHOICE_AUTO,
    /** Normalized completion reason name, e.g. "STOP" or "TOOL_CALLS"; "UNKNOWN" when unreported. */
    val finishReason: String = "UNKNOWN",
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val fallbackReason: String? = null,
) {
    companion object {
        const val TOOL_CHOICE_AUTO = "auto"
        const val TOOL_CHOICE_REQUIRED = "required"
    }
}

/** Reading side of a turn, rebuilt from a stored step; every field optional. */
data class ModelTurnView(
    val turn: Int?,
    val provider: String?,
    val model: String?,
    val requestedProvider: String?,
    val requestedModel: String?,
    val toolChoice: String?,
    val finishReason: String?,
    val promptTokens: Int?,
    val outputTokens: Int?,
    val durationMs: Long?,
    val fallbackReason: String?,
) {
    /** True when a different provider or model than the requested one served this turn. */
    val switched: Boolean
        get() {
            val askedProvider = requestedProvider?.takeIf { it.isNotBlank() }
            val askedModel = requestedModel?.takeIf { it.isNotBlank() }
            if (askedProvider == null && askedModel == null) return false
            return (askedProvider != null && provider != null && askedProvider != provider) ||
                (askedModel != null && model != null && askedModel != model)
        }
}

/** Step encoding of a [ModelTurnRecord]; the keys are stable because Room and the JSON store keep them. */
object ModelTurnSteps {
    const val NAME = "model_turn"
    /** A run keeps at most this many turns (the newest are dropped once it is reached). */
    const val MAX_TURNS = 64

    private const val K_TURN = "turn"
    private const val K_PROVIDER = "provider"
    private const val K_MODEL = "model"
    private const val K_REQ_PROVIDER = "requested_provider"
    private const val K_REQ_MODEL = "requested_model"
    private const val K_TOOL_CHOICE = "tool_choice"
    private const val K_FINISH = "finish_reason"
    private const val K_PROMPT = "prompt_tokens"
    private const val K_OUTPUT = "output_tokens"
    private const val K_FALLBACK = "fallback_reason"

    fun isModelTurn(step: AgentStep): Boolean = step.type == AgentStepType.MODEL_TURN

    fun toStep(record: ModelTurnRecord, redact: (String) -> String = { it }): AgentStep {
        val params = linkedMapOf(
            K_TURN to record.turn.toString(),
            K_PROVIDER to record.providerId.take(60),
            K_MODEL to record.modelId.take(80),
            K_TOOL_CHOICE to record.toolChoice,
            K_FINISH to record.finishReason,
            K_PROMPT to record.promptTokens.coerceAtLeast(0).toString(),
            K_OUTPUT to record.outputTokens.coerceAtLeast(0).toString(),
        )
        if (record.requestedProviderId.isNotBlank()) params[K_REQ_PROVIDER] = record.requestedProviderId.take(60)
        if (record.requestedModelId.isNotBlank()) params[K_REQ_MODEL] = record.requestedModelId.take(80)
        record.fallbackReason?.takeIf { it.isNotBlank() }?.let { params[K_FALLBACK] = redact(it).take(160) }
        return AgentStep(
            stepIndex = 0,
            type = AgentStepType.MODEL_TURN,
            name = NAME,
            inputParams = params,
            outputSummary = "",
            success = true,
            durationMs = record.durationMs.coerceAtLeast(0L),
            timestampMs = record.timestampMs,
        )
    }

    fun view(step: AgentStep): ModelTurnView? {
        if (!isModelTurn(step)) return null
        val p = step.inputParams
        fun s(key: String) = p[key]?.takeIf { it.isNotBlank() }
        return ModelTurnView(
            turn = s(K_TURN)?.toIntOrNull(),
            provider = s(K_PROVIDER),
            model = s(K_MODEL),
            requestedProvider = s(K_REQ_PROVIDER),
            requestedModel = s(K_REQ_MODEL),
            toolChoice = s(K_TOOL_CHOICE),
            finishReason = s(K_FINISH),
            promptTokens = s(K_PROMPT)?.toIntOrNull(),
            outputTokens = s(K_OUTPUT)?.toIntOrNull(),
            durationMs = step.durationMs.takeIf { it > 0 },
            fallbackReason = s(K_FALLBACK),
        )
    }
}
