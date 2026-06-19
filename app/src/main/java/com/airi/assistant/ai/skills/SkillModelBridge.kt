package com.airi.assistant.ai.skills

import android.content.Context
import android.util.Log
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.HybridOrchestrator

/**
 * SkillModelBridge — gives skills access to the active LLM without exposing
 * the full [HybridOrchestrator] API surface.
 *
 * Skills that declare [SkillModelAccess.CHAT] or [SkillModelAccess.CHAT_WITH_ROUTING]
 * receive this bridge via [SkillContext.modelBridge] and can call
 * [complete] to get a single-turn text completion from the model.
 */
interface SkillModelBridge {
    /**
     * Run a single-turn completion.
     *
     * @param prompt       The user-facing content.
     * @param systemPrompt Optional system instruction override.
     * @param maxTokens    Soft token budget hint for the backend.
     * @return             The model's text response, or an error message.
     */
    suspend fun complete(
        prompt:       String,
        systemPrompt: String = "You are a helpful AI assistant. Complete the task accurately and concisely.",
        maxTokens:    Int    = 512
    ): String

    companion object {
        /**
         * Create a bridge backed by a live [HybridOrchestrator].
         * When orchestrator is null, returns a no-op bridge that clearly
         * informs the skill that no model is available.
         */
        fun create(orchestrator: HybridOrchestrator?, context: Context): SkillModelBridge =
            if (orchestrator != null) {
                HybridOrchestratorModelBridge(orchestrator, context)
            } else {
                NoOpModelBridge
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private class HybridOrchestratorModelBridge(
    private val orchestrator: HybridOrchestrator,
    private val context:      Context
) : SkillModelBridge {

    override suspend fun complete(
        prompt:       String,
        systemPrompt: String,
        maxTokens:    Int
    ): String {
        val sb = StringBuilder()
        var errorMsg: String? = null

        try {
            val request = ExecutionRequest(
                prompt       = prompt,
                systemPrompt = systemPrompt,
                maxTokens    = maxTokens
            )
            orchestrator.executeStream(
                request    = request,
                context    = context,
                onToken    = { token -> sb.append(token) },
                onComplete = { _, _, _ -> },
                onError    = { err, _ -> errorMsg = err }
            )
        } catch (e: Exception) {
            Log.e("SkillModelBridge", "Model completion failed: ${e.message}")
            return "Error: Model call failed — ${e.message ?: "unknown error"}"
        }

        if (errorMsg != null) {
            return "Model error: $errorMsg"
        }

        val response = sb.toString().trim()
        return if (response.isBlank()) "No response from model." else response
    }
}

// ─────────────────────────────────────────────────────────────────────────────

private object NoOpModelBridge : SkillModelBridge {
    override suspend fun complete(prompt: String, systemPrompt: String, maxTokens: Int): String =
        "Model not available. Load a local model or configure a cloud provider in Settings → AI Models."
}
