package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.connector.ConnectorAgentParameter
import com.airi.assistant.domain.tool.TypedArgumentSpec

object TypedArgumentAdapters {
    fun fromSchema(schema: ToolSchema): List<TypedArgumentSpec> =
        schema.parameters.map { (name, parameter) -> fromParam(name, parameter) }

    fun fromSkill(parameters: Map<String, SkillParamDef>): List<TypedArgumentSpec> =
        parameters.map { (name, parameter) -> fromSkillParam(name, parameter) }

    fun fromConnector(parameters: Map<String, ConnectorAgentParameter>): List<TypedArgumentSpec> =
        parameters.map { (name, parameter) -> fromConnectorParam(name, parameter) }

    private fun fromParam(name: String, parameter: ToolSchema.Param): TypedArgumentSpec =
        TypedArgumentSpec(
            name = name, type = parameter.type.toType(), required = parameter.required,
            nullable = parameter.nullable, defaultValue = parameter.defaultValue,
            description = parameter.description, minLength = parameter.minLength,
            maxLength = parameter.maxLength, minInt = parameter.minInt, maxInt = parameter.maxInt,
            minNumber = parameter.minNumber?.toBigDecimal(), maxNumber = parameter.maxNumber?.toBigDecimal(),
            allowedValues = parameter.allowedValues,
            items = parameter.items?.let { fromParam("item", it) },
            properties = parameter.properties.mapValues { (key, value) -> fromParam(key, value) },
        )

    private fun fromSkillParam(name: String, parameter: SkillParamDef): TypedArgumentSpec =
        TypedArgumentSpec(
            name = name, type = parameter.type.toType(), required = parameter.required,
            nullable = parameter.nullable, defaultValue = parameter.defaultValue,
            description = parameter.description, minLength = parameter.minLength,
            maxLength = parameter.maxLength, minInt = parameter.minInt, maxInt = parameter.maxInt,
            minNumber = parameter.minNumber?.toBigDecimal(), maxNumber = parameter.maxNumber?.toBigDecimal(),
            allowedValues = parameter.allowedValues,
            items = parameter.items?.let { fromSkillParam("item", it) },
            properties = parameter.properties.mapValues { (key, value) -> fromSkillParam(key, value) },
        )

    private fun fromConnectorParam(name: String, parameter: ConnectorAgentParameter): TypedArgumentSpec =
        TypedArgumentSpec(
            name = name, type = parameter.type.toType(), required = parameter.required,
            nullable = parameter.nullable, defaultValue = parameter.defaultValue,
            description = parameter.description, minLength = parameter.minLength,
            maxLength = parameter.maxLength, minInt = parameter.minInt, maxInt = parameter.maxInt,
            minNumber = parameter.minNumber?.toBigDecimal(), maxNumber = parameter.maxNumber?.toBigDecimal(),
            allowedValues = parameter.allowedValues,
            items = parameter.items?.let { fromConnectorParam("item", it) },
            properties = parameter.properties.mapValues { (key, value) -> fromConnectorParam(key, value) },
        )

    private fun String.toType(): TypedArgumentSpec.Type = when (lowercase()) {
        "int", "integer" -> TypedArgumentSpec.Type.INTEGER
        "number", "decimal", "float", "double" -> TypedArgumentSpec.Type.NUMBER
        "boolean", "bool" -> TypedArgumentSpec.Type.BOOLEAN
        "object", "map" -> TypedArgumentSpec.Type.OBJECT
        "array", "list" -> TypedArgumentSpec.Type.LIST
        "url", "uri" -> TypedArgumentSpec.Type.URL
        "date" -> TypedArgumentSpec.Type.DATE
        "enum" -> TypedArgumentSpec.Type.ENUM
        else -> TypedArgumentSpec.Type.STRING
    }
}
