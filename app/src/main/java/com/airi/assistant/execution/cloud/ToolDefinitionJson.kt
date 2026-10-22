package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest

/** Provider-neutral tool declaration serializer helpers. */
internal object ToolDefinitionJson {
    fun openAiFunction(definition: ExecutionRequest.ToolDefinition): String = buildString {
        append("{\"type\":\"function\",\"function\":{")
        append("\"name\":").append(q(definition.name))
        append(",\"description\":").append(q(definition.description))
        append(",\"parameters\":").append(parameters(definition, uppercaseType = false))
        append("}}")
    }

    fun anthropicTool(definition: ExecutionRequest.ToolDefinition): String = buildString {
        append("{\"name\":").append(q(definition.name))
        append(",\"description\":").append(q(definition.description))
        append(",\"input_schema\":").append(parameters(definition, uppercaseType = false))
        append("}")
    }

    fun openAiResponseFunction(definition: ExecutionRequest.ToolDefinition): String = buildString {
        append("{\"type\":\"function\",\"name\":").append(q(definition.name))
        append(",\"description\":").append(q(definition.description))
        append(",\"parameters\":").append(parameters(definition, uppercaseType = false))
        append(",\"strict\":false}")
    }

    fun geminiDeclaration(definition: ExecutionRequest.ToolDefinition): String = buildString {
        append("{\"name\":").append(q(definition.name))
        append(",\"description\":").append(q(definition.description))
        append(",\"parameters\":").append(parameters(definition, uppercaseType = true))
        append("}")
    }

    private fun parameters(definition: ExecutionRequest.ToolDefinition, uppercaseType: Boolean): String = buildString {
        append("{\"type\":").append(q(if (uppercaseType) "OBJECT" else "object"))
        append(",\"properties\":{")
        definition.parameters.forEachIndexed { index, parameter ->
            if (index > 0) append(",")
            append(q(parameter.name)).append(":{\"type\":")
            append(q(if (uppercaseType) parameter.type.uppercase() else parameter.type.lowercase()))
            if (parameter.description.isNotBlank()) append(",\"description\":").append(q(parameter.description))
            if (parameter.enumValues.isNotEmpty()) {
                append(",\"enum\":[")
                parameter.enumValues.forEachIndexed { enumIndex, value ->
                    if (enumIndex > 0) append(",")
                    append(q(value))
                }
                append("]")
            }
            append("}")
        }
        append("}")
        val required = definition.parameters.filter { it.required }.map { it.name }
        if (required.isNotEmpty()) {
            append(",\"required\":[")
            required.forEachIndexed { index, name ->
                if (index > 0) append(",")
                append(q(name))
            }
            append("]")
        }
        append("}")
    }

    fun q(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
