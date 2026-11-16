package com.airi.assistant.ai.skills

/**
 * Canonical instruction catalog for first-party skills. It is deliberately
 * context-free so discovery and load_skill use the same source on Android and
 * in unit tests; live connector/model gates remain in SkillRegistry and
 * SkillToolBridge immediately before execution.
 */
data class SkillInstruction(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val manifest: SkillManifest,
)

object SkillCatalog {
    val official: List<SkillInstruction> by lazy {
        OfficialSkillLibrary.ALL.map { entry ->
            val manifest = entry.manifest
            SkillInstruction(
                id = manifest.id,
                name = manifest.name,
                description = manifest.description,
                body = instructionsFor(manifest),
                manifest = manifest,
            )
        }
    }

    val officialIds: Set<String> get() = official.mapTo(linkedSetOf()) { it.id }

    fun find(idOrName: String): SkillInstruction? {
        val key = idOrName.trim().lowercase().replace('-', '_').replace(' ', '_')
        return official.firstOrNull {
            it.id == key || it.name.lowercase().replace(' ', '_') == key
        }
    }

    fun load(idOrName: String): Result<SkillInstruction> =
        find(idOrName)?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("Unknown official skill '$idOrName'. Available: ${officialIds.sorted().joinToString()}"))

    private fun instructionsFor(manifest: SkillManifest): String = buildString {
        appendLine("# ${manifest.name}")
        appendLine()
        appendLine("Use this skill only when the request matches: ${manifest.description}.")
        appendLine("Stable runtime identity: ${manifest.id}.")
        appendLine()
        appendLine("## Workflow")
        appendLine("1. Validate the input against the declared schema.")
        appendLine("2. Check readiness, permissions, connector actions, and model/memory requirements.")
        appendLine("3. Invoke the registered tool through SkillToolBridge; do not bypass the dispatcher.")
        appendLine("4. Preserve the structured result as evidence and report limitations or denial reasons.")
        appendLine()
        appendLine("## Input schema")
        manifest.inputSchema.ifEmpty { mapOf("input" to "string") }.forEach { (key, type) ->
            appendLine("- $key: $type")
        }
        appendLine()
        appendLine("## Tools")
        manifest.tools.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
            tool.parameters.forEach { (key, param) ->
                appendLine("  - $key: ${param.type}${if (param.required) " (required)" else " (optional)"}")
            }
        }
        if (manifest.tools.isEmpty()) appendLine("- Use the registered skill executor for this capability.")
        appendLine()
        appendLine("## Dependencies and safety")
        appendLine("- Connectors: ${manifest.connectorActionDependencies.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("- Skill dependencies: ${manifest.dependencies.ifEmpty { listOf("none") }.joinToString()}")
        appendLine("- Risk: ${manifest.riskLevel.name.lowercase()}; confirmation required: ${manifest.requiresConfirmation}.")
        appendLine("- Memory access: ${manifest.memoryAccess}; model access: ${manifest.modelAccess}.")
        manifest.limitations.forEach { appendLine("- Limitation: $it") }
        manifest.examples.forEach { appendLine("- Example: $it") }
    }.trim()
}
