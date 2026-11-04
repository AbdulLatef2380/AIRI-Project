package com.airi.assistant.execution.cloud

import java.util.Locale

/**
 * Converts AIRI's internal connector type aliases to the provider-neutral
 * JSON Schema vocabulary before any provider serializer sees them.
 */
internal object SchemaTypeNormalizer {
    fun jsonSchemaType(rawType: String): String = when (rawType.trim().lowercase(Locale.US)) {
        "int", "integer" -> "integer"
        "float", "double", "number", "decimal" -> "number"
        "bool", "boolean" -> "boolean"
        "array", "list" -> "array"
        "object", "map", "json" -> "object"
        // URL/date are string values in the subset accepted by all providers.
        "url", "uri", "date", "datetime", "text", "str", "string" -> "string"
        // Unknown aliases fail closed to a valid scalar schema rather than
        // emitting a provider-invalid token such as INT or FLOAT.
        else -> "string"
    }

    fun geminiType(rawType: String): String = jsonSchemaType(rawType).uppercase(Locale.US)
}
