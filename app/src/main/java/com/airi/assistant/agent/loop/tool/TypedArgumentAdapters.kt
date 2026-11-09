package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.connector.ConnectorAgentParameter
import com.airi.assistant.domain.tool.TypedArgumentSpec
import java.math.BigDecimal

object TypedArgumentAdapters {
    fun fromSchema(schema: ToolSchema): List<TypedArgumentSpec> = schema.parameters.map { (name, p) ->
        TypedArgumentSpec(name, p.type.toType(), p.required, p.nullable, p.defaultValue, p.description, p.minLength, p.maxLength, p.minInt, p.maxInt, p.minNumber?.toBigDecimal(), p.maxNumber?.toBigDecimal(), p.allowedValues, p.items?.let { fromParam(name, it) }, p.properties.mapValues { (key, value) -> fromParam(key, value) })
    }

    fun fromSkill(parameters: Map<String, SkillParamDef>): List<TypedArgumentSpec> = parameters.map { (name, p) ->
        TypedArgumentSpec(name, p.type.toType(), p.required, p.nullable, p.defaultValue, p.description, p.minLength, p.maxLength, p.minInt, p.maxInt, p.minNumber?.toBigDecimal(), p.maxNumber?.toBigDecimal(), p.allowedValues, p.items?.let { fromSkill(mapOf(name to it)).single() }, p.properties.mapValues { (key, value) -> fromSkill(mapOf(key to value)).single() })
    }

    fun fromConnector(parameters: Map<String, ConnectorAgentParameter>): List<TypedArgumentSpec> = parameters.map { (name, p) ->
        TypedArgumentSpec(name, p.type.toType(), p.required, p.nullable, p.defaultValue, p.description, p.minLength, p.maxLength, p.minInt, p.maxInt, p.minNumber?.toBigDecimal(), p.maxNumber?.toBigDecimal(), p.allowedValues, p.items?.let { fromConnector(mapOf(name to it)).single() }, p.properties.mapValues { (key, value) -> fromConnector(mapOf(key to value)).single() })
    }

    private fun fromParam(name: String, p: ToolSchema.Param) = TypedArgumentSpec(name, p.type.toType(), p.required, p.nullable, p.defaultValue, p.description, p.minLength, p.maxLength, p.minInt, p.maxInt, p.minNumber?.toBigDecimal(), p.maxNumber?.toBigDecimal(), p.allowedValues, p.items?.let { fromParam(name, it) }, p.properties.mapValues { (key, value) -> fromParam(key, value) })

    private fun String.toType() = when (lowercase()) {
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
