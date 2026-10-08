package com.airi.assistant.domain.tool

import java.net.URI
import java.time.LocalDate

/**
 * Strict, deterministic conversion of untrusted tool arguments into canonical
 * values. It rejects unknown keys, missing values, lossy type coercion and
 * constraint violations before a policy or handler can observe the request.
 */
object StrictArgumentValidator {
    sealed interface Value {
        data class Text(val value: String) : Value
        data class Integer(val value: Long) : Value
        data class BooleanValue(val value: Boolean) : Value
        data class Url(val value: URI) : Value
        data class Date(val value: LocalDate) : Value
        data class EnumValue(val value: String) : Value
    }

    data class ValidatedArguments(
        val values: Map<String, Value>,
        val canonical: Map<String, String>,
    )

    sealed interface Failure {
        val argument: String?
        val message: String

        data class UnknownArgument(override val argument: String) : Failure {
            override val message = "Unsupported argument '$argument'."
        }
        data class MissingArgument(override val argument: String) : Failure {
            override val message = "Missing required argument '$argument'."
        }
        data class InvalidValue(override val argument: String, val detail: String) : Failure {
            override val message = "Invalid argument '$argument': $detail."
        }
    }

    data class Result(
        val arguments: ValidatedArguments? = null,
        val failure: Failure? = null,
    ) {
        val isValid: Boolean get() = arguments != null && failure == null
    }

    fun validate(spec: ToolSpec, raw: Map<String, String>): Result {
        val unknown = raw.keys - spec.arguments.map { it.name }.toSet()
        if (unknown.isNotEmpty()) return Result(failure = Failure.UnknownArgument(unknown.sorted().first()))

        val typed = linkedMapOf<String, Value>()
        val canonical = linkedMapOf<String, String>()
        spec.arguments.forEach { argument ->
            val supplied = raw[argument.name]
            if (supplied == null) {
                if (argument.required) return Result(failure = Failure.MissingArgument(argument.name))
                return@forEach
            }
            if (supplied.isBlank()) return Result(failure = Failure.InvalidValue(argument.name, "must not be blank"))
            val parsed = parse(argument, supplied)
            if (parsed is Parse.Failure) return Result(failure = Failure.InvalidValue(argument.name, parsed.detail))
            val value = (parsed as Parse.Success).value
            typed[argument.name] = value
            canonical[argument.name] = canonicalValue(value)
        }
        return Result(ValidatedArguments(typed, canonical))
    }

    private sealed interface Parse {
        data class Success(val value: Value) : Parse
        data class Failure(val detail: String) : Parse
    }

    private fun parse(argument: ArgumentSpec, raw: String): Parse {
        val constraints = argument.constraints
        val text = raw.trim()
        return when (argument.type) {
            ArgumentSpec.Type.STRING -> {
                if (text.length < (constraints.minLength ?: 0)) Parse.Failure("must contain at least ${constraints.minLength} characters")
                else if (constraints.maxLength != null && text.length > constraints.maxLength) Parse.Failure("must contain at most ${constraints.maxLength} characters")
                else Parse.Success(Value.Text(text))
            }
            ArgumentSpec.Type.INTEGER -> {
                if (!Regex("-?(0|[1-9][0-9]*)").matches(text)) Parse.Failure("must be a base-10 integer")
                else runCatching { text.toLong() }.fold(
                    onSuccess = { number ->
                        when {
                            constraints.minNumber != null && number < constraints.minNumber -> Parse.Failure("must be >= ${constraints.minNumber}")
                            constraints.maxNumber != null && number > constraints.maxNumber -> Parse.Failure("must be <= ${constraints.maxNumber}")
                            else -> Parse.Success(Value.Integer(number))
                        }
                    },
                    onFailure = { Parse.Failure("is outside the supported integer range") },
                )
            }
            ArgumentSpec.Type.BOOLEAN -> when (text.lowercase()) {
                "true" -> Parse.Success(Value.BooleanValue(true))
                "false" -> Parse.Success(Value.BooleanValue(false))
                else -> Parse.Failure("must be exactly true or false")
            }
            ArgumentSpec.Type.URL -> {
                val uri = runCatching { URI(text) }.getOrNull()
                when {
                    uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank() -> Parse.Failure("must be an absolute URL")
                    constraints.allowedSchemes.isNotEmpty() && uri.scheme.lowercase() !in constraints.allowedSchemes -> Parse.Failure("scheme must be one of ${constraints.allowedSchemes.sorted().joinToString()}")
                    else -> Parse.Success(Value.Url(uri))
                }
            }
            ArgumentSpec.Type.DATE -> runCatching { LocalDate.parse(text) }
                .fold({ Parse.Success(Value.Date(it)) }, { Parse.Failure("must use ISO-8601 date format YYYY-MM-DD") })
            ArgumentSpec.Type.ENUM -> {
                if (constraints.allowedValues.isEmpty()) Parse.Failure("has no declared allowed values")
                else if (text !in constraints.allowedValues) Parse.Failure("must be one of ${constraints.allowedValues.sorted().joinToString()}")
                else Parse.Success(Value.EnumValue(text))
            }
        }
    }

    private fun canonicalValue(value: Value): String = when (value) {
        is Value.Text -> value.value
        is Value.Integer -> value.value.toString()
        is Value.BooleanValue -> value.value.toString()
        is Value.Url -> value.value.toASCIIString()
        is Value.Date -> value.value.toString()
        is Value.EnumValue -> value.value
    }
}
