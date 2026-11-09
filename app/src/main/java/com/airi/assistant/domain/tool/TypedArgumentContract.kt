package com.airi.assistant.domain.tool

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/** One argument contract shared by skills, tools and connectors. */
data class TypedArgumentSpec(
    val name: String,
    val type: Type,
    val required: Boolean = false,
    val nullable: Boolean = false,
    val defaultValue: Any? = null,
    val description: String = "",
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val minInt: Long? = null,
    val maxInt: Long? = null,
    val minNumber: BigDecimal? = null,
    val maxNumber: BigDecimal? = null,
    val allowedValues: Set<String> = emptySet(),
    val items: TypedArgumentSpec? = null,
    val properties: Map<String, TypedArgumentSpec> = emptyMap(),
) {
    enum class Type { STRING, INTEGER, NUMBER, BOOLEAN, OBJECT, LIST, URL, DATE, ENUM }
}

object TypedArgumentContract {
    sealed interface Value {
        data class Text(val value: String) : Value
        data class Integer(val value: Long) : Value
        data class Number(val value: BigDecimal) : Value
        data class BooleanValue(val value: Boolean) : Value
        data class ObjectValue(val value: Map<String, Value?>) : Value
        data class ListValue(val value: List<Value?>) : Value
        data class Url(val value: String) : Value
        data class Date(val value: String) : Value
        data class EnumValue(val value: String) : Value
        data object NullValue : Value
    }

    data class Validated(val values: Map<String, Value?>, val canonical: Map<String, String>)
    data class Failure(val argument: String?, val code: String, val message: String, val repairHint: String)
    data class Result(val value: Validated? = null, val failure: Failure? = null) { val isValid get() = value != null }

    fun validate(specs: Collection<TypedArgumentSpec>, raw: Map<String, Any?>): Result {
        val byName = specs.associateBy { it.name }
        raw.keys.firstOrNull { it !in byName }?.let { key ->
            return Result(failure = Failure(key, "unknown_argument", "Unsupported argument '$key'.", "Remove '$key' and use only declared arguments."))
        }
        val values = linkedMapOf<String, Value?>()
        val canonical = linkedMapOf<String, String>()
        for (spec in specs) {
            val supplied = if (raw.containsKey(spec.name)) raw[spec.name] else spec.defaultValue
            if (supplied == null) {
                if (spec.required && !spec.nullable) return Result(failure = Failure(spec.name, "missing_argument", "Missing required argument '${spec.name}'.", "Provide '${spec.name}' with the declared type."))
                if (spec.nullable) { values[spec.name] = Value.NullValue; canonical[spec.name] = "null" }
                continue
            }
            val parsed = parse(spec, supplied)
            if (parsed is Parse.Failure) return Result(failure = Failure(spec.name, "invalid_argument", parsed.message, parsed.repairHint))
            val value = (parsed as Parse.Success).value
            values[spec.name] = value
            canonical[spec.name] = encode(value)
        }
        return Result(Validated(values, canonical))
    }

    private sealed interface Parse { data class Success(val value: Value?) : Parse; data class Failure(val message: String, val repairHint: String) : Parse }

    private fun parse(spec: TypedArgumentSpec, raw: Any?): Parse {
        if (raw == null) return if (spec.nullable) Parse.Success(Value.NullValue) else Parse.Failure("Argument '${spec.name}' cannot be null.", "Provide a non-null '${spec.name}'.")
        val text = raw as? String
        return when (spec.type) {
            TypedArgumentSpec.Type.STRING -> textValue(spec, text ?: raw.toString())
            TypedArgumentSpec.Type.INTEGER -> integerValue(spec, text ?: raw.toString())
            TypedArgumentSpec.Type.NUMBER -> numberValue(spec, text ?: raw.toString())
            TypedArgumentSpec.Type.BOOLEAN -> booleanValue(spec, text ?: raw.toString())
            TypedArgumentSpec.Type.URL -> textValue(spec, text ?: raw.toString(), url = true)
            TypedArgumentSpec.Type.DATE -> textValue(spec, text ?: raw.toString())
            TypedArgumentSpec.Type.ENUM -> if (text != null && text in spec.allowedValues) Parse.Success(Value.EnumValue(text)) else Parse.Failure("Argument '${spec.name}' must be one of ${spec.allowedValues.sorted().joinToString()}.", "Choose one declared value for '${spec.name}'.")
            TypedArgumentSpec.Type.OBJECT -> parseObject(spec, raw)
            TypedArgumentSpec.Type.LIST -> parseList(spec, raw)
        }
    }

    private fun textValue(spec: TypedArgumentSpec, value: String, url: Boolean = false): Parse {
        if (value.isBlank()) return Parse.Failure("Argument '${spec.name}' must not be blank.", "Provide a non-empty '${spec.name}'.")
        if (spec.minLength != null && value.length < spec.minLength) return Parse.Failure("Argument '${spec.name}' is too short.", "Use at least ${spec.minLength} characters.")
        if (spec.maxLength != null && value.length > spec.maxLength) return Parse.Failure("Argument '${spec.name}' is too long.", "Use at most ${spec.maxLength} characters.")
        if (url && !(value.startsWith("https://") || value.startsWith("http://"))) return Parse.Failure("Argument '${spec.name}' must be an absolute URL.", "Use an http(s) URL.")
        return Parse.Success(if (url) Value.Url(value) else Value.Text(value))
    }

    private fun integerValue(spec: TypedArgumentSpec, value: String): Parse = runCatching {
        if (!Regex("-?(0|[1-9][0-9]*)").matches(value.trim())) error("not integer")
        value.trim().toLong()
    }.fold({ number ->
        when {
            spec.minInt != null && number < spec.minInt -> Parse.Failure("Argument '${spec.name}' is below the minimum.", "Use a value >= ${spec.minInt}.")
            spec.maxInt != null && number > spec.maxInt -> Parse.Failure("Argument '${spec.name}' exceeds the maximum.", "Use a value <= ${spec.maxInt}.")
            else -> Parse.Success(Value.Integer(number))
        }
    }, { Parse.Failure("Argument '${spec.name}' must be an integer.", "Send a base-10 integer without decimals.") })

    private fun numberValue(spec: TypedArgumentSpec, value: String): Parse = runCatching { BigDecimal(value.trim()) }.fold({ number ->
        when {
            spec.minNumber != null && number < spec.minNumber -> Parse.Failure("Argument '${spec.name}' is below the minimum.", "Use a number >= ${spec.minNumber}.")
            spec.maxNumber != null && number > spec.maxNumber -> Parse.Failure("Argument '${spec.name}' exceeds the maximum.", "Use a number <= ${spec.maxNumber}.")
            else -> Parse.Success(Value.Number(number))
        }
    }, { Parse.Failure("Argument '${spec.name}' must be a finite number.", "Send a decimal number.") })

    private fun booleanValue(spec: TypedArgumentSpec, value: String): Parse = when (value.trim().lowercase()) {
        "true" -> Parse.Success(Value.BooleanValue(true)); "false" -> Parse.Success(Value.BooleanValue(false))
        else -> Parse.Failure("Argument '${spec.name}' must be true or false.", "Send exactly true or false.")
    }

    private fun parseObject(spec: TypedArgumentSpec, raw: Any): Parse {
        val map: Map<String, Any?> = when (raw) {
            is Map<*, *> -> raw.entries.associate { it.key.toString() to it.value }
            is String -> runCatching { JSONObject(raw) }.getOrNull()?.let { json -> json.keys().asSequence().associateWith { json.opt(it).takeUnless { v -> v == JSONObject.NULL } } } ?: return Parse.Failure("Argument '${spec.name}' must be a JSON object.", "Send an object such as {\"key\":\"value\"}.")
            else -> return Parse.Failure("Argument '${spec.name}' must be an object.", "Send a JSON object.")
        }
        return validate(spec.properties.values, map).value?.let { Parse.Success(Value.ObjectValue(it.values)) }
            ?: Parse.Failure("Argument '${spec.name}' contains an invalid property.", "Fix the object properties to match the declared schema.")
    }

    private fun parseList(spec: TypedArgumentSpec, raw: Any): Parse {
        val list: List<Any?> = when (raw) {
            is List<*> -> raw
            is String -> runCatching { JSONArray(raw) }.getOrNull()?.let { arr -> (0 until arr.length()).map { arr.opt(it).takeUnless { v -> v == JSONObject.NULL } } } ?: return Parse.Failure("Argument '${spec.name}' must be a JSON list.", "Send an array such as [\"value\"].")
            else -> return Parse.Failure("Argument '${spec.name}' must be a list.", "Send a JSON array.")
        }
        val itemSpec = spec.items ?: return Parse.Failure("Argument '${spec.name}' has no item schema.", "The tool schema must declare list items.")
        val parsed = list.map { parse(itemSpec, it) }
        val failure = parsed.filterIsInstance<Parse.Failure>().firstOrNull()
        return failure ?: Parse.Success(Value.ListValue(parsed.map { (it as Parse.Success).value }))
    }

    private fun encode(value: Value?): String = when (value) {
        null, Value.NullValue -> "null"
        is Value.Text -> value.value
        is Value.Integer -> value.value.toString()
        is Value.Number -> value.value.stripTrailingZeros().toPlainString()
        is Value.BooleanValue -> value.value.toString()
        is Value.Url -> value.value
        is Value.Date -> value.value
        is Value.EnumValue -> value.value
        is Value.ObjectValue -> value.value.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { "${it.key}:${encode(it.value)}" }
        is Value.ListValue -> value.value.joinToString(prefix = "[", postfix = "]") { encode(it) }
    }
}
