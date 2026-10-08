package com.airi.assistant.agent.loop.tool

import com.airi.assistant.domain.tool.ArgumentSpec
import com.airi.assistant.domain.tool.ToolSpec

/** Converts the exposed runtime schema into the canonical domain contract. */
object ToolSpecAdapter {
    fun fromSchema(schema: ToolSchema): ToolSpec = ToolSpec(
        id = schema.name,
        arguments = schema.parameters.map { (name, parameter) ->
            ArgumentSpec(
                name = name,
                type = parameter.type.toArgumentType(),
                required = parameter.required,
                constraints = ArgumentSpec.Constraints(
                    minLength = parameter.minLength,
                    maxLength = parameter.maxLength,
                    minNumber = parameter.minInt,
                    maxNumber = parameter.maxInt,
                    allowedValues = parameter.allowedValues,
                    allowedSchemes = parameter.allowedSchemes.map { it.lowercase() }.toSet(),
                ),
            )
        },
        capability = schema.name,
        risk = if (schema.dangerous) ToolSpec.Risk.SIDE_EFFECT else ToolSpec.Risk.READ,
        privacy = if (schema.category == ToolSchema.Category.EXTERNAL) {
            ToolSpec.PrivacyBoundary.CLOUD_ALLOWED
        } else {
            ToolSpec.PrivacyBoundary.LOCAL_ONLY
        },
        requiresConfirmation = schema.dangerous,
        idempotent = !schema.dangerous,
        handlerKey = schema.name,
    )

    private fun String.toArgumentType(): ArgumentSpec.Type = when (lowercase()) {
        "int", "integer", "number" -> ArgumentSpec.Type.INTEGER
        "boolean", "bool" -> ArgumentSpec.Type.BOOLEAN
        "url", "uri" -> ArgumentSpec.Type.URL
        "date" -> ArgumentSpec.Type.DATE
        "enum" -> ArgumentSpec.Type.ENUM
        else -> ArgumentSpec.Type.STRING
    }
}
