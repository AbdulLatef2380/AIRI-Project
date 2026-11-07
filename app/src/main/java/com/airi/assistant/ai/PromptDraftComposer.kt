package com.airi.assistant.ai

data class PromptDraftInput(
    val goal: String,
    val audience: String = "",
    val constraints: String = "",
    val modelPreference: String = "",
    val tone: String = "",
    val skills: List<String> = emptyList(),
    val connectors: List<String> = emptyList(),
)

enum class PromptDraftKind { FOCUSED, AGENT_WORKFLOW }

data class PromptDraft(
    val kind: PromptDraftKind,
    val body: String,
)

data class PromptDraftTemplates(
    val focusedFormat: String,
    val agentWorkflowFormat: String,
    val unspecifiedAudience: String,
    val unspecifiedConstraints: String,
    val noneSelected: String,
)

/** Pure prompt composition. It never chooses a provider, grants tool access, or executes the draft. */
object PromptDraftComposer {
    private val promptTokenPattern = Regex("""\{\{(?:GOAL|AUDIENCE|CONSTRAINTS|MODEL_PREFERENCE|TONE|SKILLS|CONNECTORS)}}""")

    fun generate(input: PromptDraftInput, templates: PromptDraftTemplates): List<PromptDraft> {
        val goal = input.goal.trim()
        if (goal.isBlank()) return emptyList()

        val audience = input.audience.trim().ifBlank { templates.unspecifiedAudience }
        val constraints = input.constraints.trim().ifBlank { templates.unspecifiedConstraints }
        val modelPreference = input.modelPreference.trim().ifBlank { templates.noneSelected }
        val tone = input.tone.trim().ifBlank { templates.noneSelected }
        val skills = input.skills.normalizedSelection(templates.noneSelected)
        val connectors = input.connectors.normalizedSelection(templates.noneSelected)
        val replacements = listOf(
            "{{GOAL}}" to goal,
            "{{AUDIENCE}}" to audience,
            "{{CONSTRAINTS}}" to constraints,
            "{{MODEL_PREFERENCE}}" to modelPreference,
            "{{TONE}}" to tone,
            "{{SKILLS}}" to skills,
            "{{CONNECTORS}}" to connectors,
        )

        return listOf(
            PromptDraft(
                kind = PromptDraftKind.FOCUSED,
                body = templates.focusedFormat.applyPromptValues(replacements),
            ),
            PromptDraft(
                kind = PromptDraftKind.AGENT_WORKFLOW,
                body = templates.agentWorkflowFormat.applyPromptValues(replacements),
            ),
        )
    }

    private fun List<String>.normalizedSelection(noneSelected: String): String =
        asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .joinToString(", ")
            .ifBlank { noneSelected }

    private fun String.applyPromptValues(replacements: List<Pair<String, String>>): String {
        val values = replacements.toMap()
        return promptTokenPattern.replace(this) { match -> values[match.value] ?: match.value }
    }
}
