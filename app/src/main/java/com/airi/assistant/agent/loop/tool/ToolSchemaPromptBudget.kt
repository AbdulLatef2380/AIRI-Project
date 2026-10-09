package com.airi.assistant.agent.loop.tool

/** Keeps prompt exposure and the AgentLoop dispatch allowlist identical under context pressure. */
object ToolSchemaPromptBudget {
    data class Selection(
        val tools: List<ToolSchema>,
        val promptBlock: String,
        val omittedCount: Int,
    )

    fun select(tools: List<ToolSchema>, maxChars: Int): Selection {
        val budget = maxChars.coerceAtLeast(0)
        val header = "AVAILABLE TOOLS:\n"
        if (tools.isEmpty()) return Selection(emptyList(), header, 0)
        if (budget < header.length) return Selection(emptyList(), "AVAILABLE TOOLS:".take(budget), tools.size)

        val chunks = tools.map(::renderTool)
        val selected = mutableListOf<ToolSchema>()
        val selectedChunks = mutableListOf<String>()
        var length = header.length
        val footerReserve = OMITTED_SUFFIX_RESERVE
        chunks.forEachIndexed { index, chunk ->
            val hasLaterCandidate = index < chunks.lastIndex
            val reserve = if (hasLaterCandidate) footerReserve else 0
            if (length + chunk.length + reserve <= budget) {
                selected += tools[index]
                selectedChunks += chunk
                length += chunk.length
            }
        }

        var omitted = tools.size - selected.size
        var block = header + selectedChunks.joinToString("")
        if (omitted > 0) {
            val suffix = "[${omitted} tool schemas omitted by the context budget; omitted tools are not available in this request.]\n"
            while (block.length + suffix.length > budget && selected.isNotEmpty()) {
                selected.removeAt(selected.lastIndex)
                selectedChunks.removeAt(selectedChunks.lastIndex)
                omitted++
                block = header + selectedChunks.joinToString("")
            }
            val finalSuffix = "[${omitted} tool schemas omitted by context budget; omitted tools are not available in this request.]\n"
            block += finalSuffix.take((budget - block.length).coerceAtLeast(0))
        }
        return Selection(selected, block, omitted)
    }

    private fun renderTool(tool: ToolSchema): String = buildString {
        append("• ${tool.name}: ${tool.description}\n")
        if (tool.parameters.isNotEmpty()) {
            append("  Parameters: ${tool.parameters.entries.joinToString(", ") {
                "${it.key} (${it.value.type}${if (it.value.required) ", required" else ""})"
            }}\n")
        }
    }

    private const val OMITTED_SUFFIX_RESERVE = 110
}
