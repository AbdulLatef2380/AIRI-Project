package com.airi.assistant.ui.composer

/**
 * Pure rules for the chat composer directives.
 *
 * Skill directives are kept at the beginning of the input so they can be
 * rendered as compact chips while the actual task text remains editable.
 */
object ComposerDirectivePolicy {
    enum class SkillBackspaceAction { OPEN_PICKER, ARM_REMOVAL, REMOVE_SKILL }

    private val skillDirective = Regex("^/skill:([A-Za-z0-9_.-]+)\\s*")
    private val knowledgeDirective = Regex("^@knowledge:([A-Za-z0-9-]+)\\s*")
    fun nextSkillBackspaceAction(stage: Int): SkillBackspaceAction = when (stage) {
        0 -> SkillBackspaceAction.OPEN_PICKER
        1 -> SkillBackspaceAction.ARM_REMOVAL
        else -> SkillBackspaceAction.REMOVE_SKILL
    }

    /**
     * Replaces the active slash/at query, or appends a skill when the query is
     * typed after already-selected leading directives (for example
     * `/skill:web_search /`).
     */
    fun applySelection(currentText: String, directiveId: String, isKnowledge: Boolean): String {
        val trimmed = currentText.trimStart()
        val directive = if (isKnowledge) "@knowledge:$directiveId" else "/skill:$directiveId"
        val (prefix, suffix) = splitLeadingDirectives(trimmed)

        // Selecting an already-visible directive must be idempotent. Apart from
        // preventing duplicate Compose keys, this keeps the user's task text
        // intact when the picker is opened again for the same skill.
        val selectedIds = if (isKnowledge) leadingKnowledgeIds(trimmed) else leadingSkillIds(trimmed)
        if (directiveId in selectedIds) {
            val queryPrefix = if (isKnowledge) "@" else "/"
            val remaining = suffix.trimStart().let { candidate ->
                if (candidate.startsWith(queryPrefix)) {
                    val queryEnd = candidate.indexOfFirst(Char::isWhitespace)
                        .let { if (it < 0) candidate.length else it }
                    candidate.substring(queryEnd).trimStart()
                } else candidate
            }
            return buildDirectiveText(prefix, remaining)
        }

        // Replace the leading picker query (`/web` or `@docs`) instead of
        // treating it as task text. Matching is limited to the first token so
        // slashes and at-signs inside the actual task remain untouched.
        if (prefix.isBlank()) {
            val queryPrefix = if (isKnowledge) "@" else "/"
            if (suffix.startsWith(queryPrefix) && !suffix.startsWith(directive)) {
                // Only the first whitespace-delimited token is the picker query.
                // This preserves URLs, shell paths, and @mentions in the task.
                val queryEnd = suffix.indexOfFirst(Char::isWhitespace)
                    .let { if (it < 0) suffix.length else it }
                val task = suffix.substring(queryEnd).trimStart()
                return if (task.isBlank()) "$directive " else "$directive $task"
            }
        }

        if (!isKnowledge && prefix.isNotBlank() && suffix.trimStart().startsWith("/")) {
            val remainingQuery = suffix.trimStart().removePrefix("/").trimStart()
            return buildDirectiveText(
                prefix = "$prefix$directive ",
                suffix = remainingQuery,
            )
        }

        return if (suffix.isBlank()) "$directive " else "$directive ${suffix.trimStart()}"
    }

    /** Returns all leading skill IDs in their visual/order-of-application order. */
    fun leadingSkillIds(text: String): List<String> {
        return leadingIds(text, skillDirective)
    }

    /** Removes all leading skill and knowledge directives, leaving task text. */
    fun taskText(text: String): String {
        var remaining = text.trimStart()
        while (true) {
            val match = skillDirective.matchEntirePrefix(remaining)
                ?: knowledgeDirective.matchEntirePrefix(remaining)
                ?: break
            remaining = remaining.removeRange(match.range).trimStart()
        }
        return remaining
    }

    /** Rebuilds the input after the editable task text has changed. */
    fun withTaskText(text: String, taskText: String): String {
        val leading = text.trimStart().takeWhileDirectives()
        return buildDirectiveText(leading, taskText)
    }

    /** Removes exactly one selected skill and keeps all other directives/task text. */
    fun removeSkill(text: String, skillId: String): String {
        var remaining = text.trimStart()
        var removed = false
        val kept = StringBuilder()
        while (true) {
            val skill = skillDirective.matchEntirePrefix(remaining)
            val knowledge = knowledgeDirective.matchEntirePrefix(remaining)
            val match = skill ?: knowledge ?: break
            val id = skill?.groupValues?.get(1)
            if (!removed && id == skillId) {
                removed = true
            } else {
                kept.append(match.value)
            }
            remaining = remaining.removeRange(match.range).trimStart()
        }
        return buildDirectiveText(kept.toString(), remaining)
    }

    private fun buildDirectiveText(prefix: String, suffix: String): String {
        val normalizedPrefix = prefix.trim()
        val normalizedSuffix = suffix.trimStart()
        return when {
            normalizedPrefix.isBlank() && normalizedSuffix.isBlank() -> ""
            normalizedPrefix.isBlank() -> normalizedSuffix
            normalizedSuffix.isBlank() -> "$normalizedPrefix "
            else -> "$normalizedPrefix $normalizedSuffix"
        }
    }

    private fun splitLeadingDirectives(text: String): Pair<String, String> {
        var remaining = text
        val prefix = StringBuilder()
        while (true) {
            val match = skillDirective.matchEntirePrefix(remaining)
                ?: knowledgeDirective.matchEntirePrefix(remaining)
                ?: break
            prefix.append(match.value)
            remaining = remaining.removeRange(match.range).trimStart()
        }
        return prefix.toString() to remaining
    }

    private fun leadingKnowledgeIds(text: String): List<String> = leadingIds(text, knowledgeDirective)

    private fun leadingIds(text: String, directive: Regex): List<String> {
        var remaining = text.trimStart()
        val result = mutableListOf<String>()
        while (true) {
            val match = directive.matchEntirePrefix(remaining) ?: break
            result += match.groupValues[1]
            remaining = remaining.removeRange(match.range).trimStart()
        }
        return result.distinct()
    }

    private fun String.takeWhileDirectives(): String = splitLeadingDirectives(this).first

    private fun Regex.matchEntirePrefix(value: String): MatchResult? = find(value)?.takeIf { it.range.first == 0 }
}
