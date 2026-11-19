package com.airi.assistant.agent.loop.tool

/**
 * Materializes references to results of earlier tool calls.
 *
 * Supported reference forms are `$call-id` and `{{call-id}}`. References are
 * resolved only from results registered for an earlier call in this execution;
 * missing or malformed references fail closed with [ToolErrorCodes.DEPENDENCY_UNRESOLVED].
 */
class ToolCallDependencyResolver {
    sealed class Resolution {
        data class Resolved(val arguments: Map<String, String>) : Resolution()
        data class Unresolved(val reference: String, val reason: String) : Resolution()
    }

    private val results = linkedMapOf<String, String>()
    private val dollarReference = Regex("\\$([A-Za-z0-9][A-Za-z0-9._:-]*)")
    private val braceReference = Regex("\\{\\{([^{}]+)\\}\\}")

    fun resolve(arguments: Map<String, String>): Resolution {
        val resolved = linkedMapOf<String, String>()
        arguments.forEach { (key, value) ->
            val malformed = malformedReference(value)
            if (malformed != null) return Resolution.Unresolved(malformed, "malformed reference")
            val references = buildList {
                dollarReference.findAll(value).forEach { add(it.value to it.groupValues[1]) }
                braceReference.findAll(value).forEach { add(it.value to it.groupValues[1].trim()) }
            }
            var materialized = value
            references.forEach { (token, id) ->
                val result = results[id] ?: return Resolution.Unresolved(token, "unknown or non-prior toolCallId")
                materialized = materialized.replace(token, result)
            }
            resolved[key] = materialized
        }
        return Resolution.Resolved(resolved)
    }

    /** Registers a completed tool result for subsequent calls in this execution. */
    fun registerResult(toolCallId: String, result: String) {
        val id = toolCallId.trim()
        if (id.isNotEmpty()) results[id] = result
    }

    fun knownToolCallIds(): Set<String> = results.keys.toSet()

    private fun malformedReference(value: String): String? {
        val openBraces = value.indexOf("{{")
        val closeBraces = value.indexOf("}}")
        if (openBraces >= 0 && (closeBraces < openBraces || value.indexOf("{{", openBraces + 2) >= 0 && value.indexOf("}}", openBraces + 2) < 0)) {
            return value.substring(openBraces)
        }
        val dollar = value.indexOf('$')
        if (dollar >= 0 && dollar == value.length - 1) return value.substring(dollar)
        if (dollar >= 0 && value[dollar + 1].isWhitespace()) return value.substring(dollar)
        if (value.contains("\$call-") && dollarReference.find(value) == null) return value.substring(dollar)
        return null
    }
}
