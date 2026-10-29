package com.airi.assistant.agent.loop.tool

/** Keeps prompt exposure and the AgentLoop dispatch allowlist identical under context pressure. */
object ToolSchemaPromptBudget {
    data class Selection(
        val tools: List<ToolSchema>,
        val promptBlock: String,
        val omittedCount: Int,
        val omittedToolNames: List<String> = emptyList(),
        val requiredButOmittedToolNames: List<String> = emptyList(),
    )

    fun select(
        tools: List<ToolSchema>,
        maxChars: Int,
        requiredToolNames: Set<String> = tools.filter { it.requiredForRequest }.map { it.name }.toSet(),
    ): Selection {
        val budget = maxChars.coerceAtLeast(0)
        val header = "AVAILABLE TOOLS:\n"
        if (tools.isEmpty()) return Selection(emptyList(), header, 0)
        if (budget < header.length) return Selection(
            emptyList(),
            "AVAILABLE TOOLS:".take(budget),
            tools.size,
            tools.map { it.name },
            tools.map { it.name }.filter { it in requiredToolNames },
        )

        val orderedTools = tools.sortedWith(compareByDescending<ToolSchema> { it.name in requiredToolNames })
        val chunks = orderedTools.map(::renderTool)
        val selected = mutableListOf<ToolSchema>()
        val selectedChunks = mutableListOf<String>()
        var length = header.length
        val footerReserve = OMITTED_SUFFIX_RESERVE
        chunks.forEachIndexed { index, chunk ->
            val hasLaterCandidate = index < chunks.lastIndex
            val reserve = if (orderedTools[index].name in requiredToolNames) 0
                else if (hasLaterCandidate) footerReserve else 0
            if (length + chunk.length + reserve <= budget) {
                selected += orderedTools[index]
                selectedChunks += chunk
                length += chunk.length
            }
        }

        var omitted = orderedTools.size - selected.size
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
        return Selection(
            tools = selected,
            promptBlock = block,
            omittedCount = omitted,
            omittedToolNames = orderedTools.map { it.name }.filterNot { name -> selected.any { it.name == name } },
            requiredButOmittedToolNames = orderedTools.map { it.name }
                .filter { it in requiredToolNames && selected.none { tool -> tool.name == it } },
        )
    }

    private fun renderTool(tool: ToolSchema): String = buildString {
        append("• ${tool.name}: ${tool.description}\n")
        if (tool.parameters.isNotEmpty()) {
            append("  Parameters: ${tool.parameters.entries.joinToString(", ") {
                val parameter = it.value
                buildString {
                    append("${it.key} (${parameter.type}${if (parameter.required) ", required" else ""})")
                    parameter.description.takeIf(String::isNotBlank)?.let { description -> append(": $description") }
                    val constraints = buildList {
                        parameter.minLength?.let { add("minLength=$it") }
                        parameter.maxLength?.let { add("maxLength=$it") }
                        parameter.minInt?.let { add("min=$it") }
                        parameter.maxInt?.let { add("max=$it") }
                        parameter.minNumber?.let { add("min=$it") }
                        parameter.maxNumber?.let { add("max=$it") }
                        if (parameter.allowedValues.isNotEmpty()) add("oneOf=${parameter.allowedValues.sorted().joinToString("|")}")
                        if (parameter.allowedSchemes.isNotEmpty()) add("schemes=${parameter.allowedSchemes.sorted().joinToString("|")}")
                    }
                    if (constraints.isNotEmpty()) append(" [${constraints.joinToString(", ")}]")
                }
            }}\n")
        }
    }

    private const val OMITTED_SUFFIX_RESERVE = 110
}
