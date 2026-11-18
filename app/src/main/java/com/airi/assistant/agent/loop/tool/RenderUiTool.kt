package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ui.generative.GenerativeUiCompileResult
import com.airi.assistant.ui.generative.GenerativeUiCompiler

/**
 * Pure adapter for the model-facing render_ui tool.
 *
 * It never renders Compose directly and never executes an action. It validates the
 * document with the same compiler used by the fallback fence path, then returns a
 * canonical fenced representation for the normal assistant response pipeline.
 */
object RenderUiTool {
    sealed interface Result {
        data class Accepted(val content: String, val blockCount: Int) : Result
        data class Rejected(val reason: String) : Result
    }

    fun render(document: String, text: String? = null): Result {
        val raw = document.trim()
        if (raw.isBlank() || raw.contains("```airi-ui", ignoreCase = true)) {
            return Result.Rejected("document must be JSON without an airi-ui fence")
        }
        val candidate = "```airi-ui\n$raw\n```"
        return when (val compiled = GenerativeUiCompiler.compileDetailed(candidate)) {
            is GenerativeUiCompileResult.Accepted -> {
                val prefix = text?.trim()?.takeIf { it.isNotBlank() }?.let { "$it\n\n" }.orEmpty()
                Result.Accepted(prefix + candidate, compiled.value.document.blocks.size)
            }
            is GenerativeUiCompileResult.Rejected ->
                Result.Rejected("GenUI document rejected: ${compiled.reason.name.lowercase()}")
        }
    }
}
