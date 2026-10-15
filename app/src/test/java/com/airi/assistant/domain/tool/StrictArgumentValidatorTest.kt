package com.airi.assistant.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictArgumentValidatorTest {
    private val spec = ToolSpec(
        id = "sample",
        capability = "sample",
        risk = ToolSpec.Risk.READ,
        privacy = ToolSpec.PrivacyBoundary.LOCAL_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        handlerKey = "sample",
        arguments = listOf(
            ArgumentSpec(
                name = "count",
                type = ArgumentSpec.Type.INTEGER,
                required = true,
                constraints = ArgumentSpec.Constraints(minNumber = 1, maxNumber = 10),
            ),
            ArgumentSpec(
                name = "url",
                type = ArgumentSpec.Type.URL,
                required = true,
                constraints = ArgumentSpec.Constraints(allowedSchemes = setOf("https")),
            ),
            ArgumentSpec("enabled", ArgumentSpec.Type.BOOLEAN, required = false),
        ),
    )

    @Test
    fun convertsTypesAndProducesCanonicalValues() {
        val result = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to " 01 ", "url" to "https://example.com/a b", "enabled" to "TRUE"),
        )

        // Leading-zero integers are rejected rather than silently reinterpreted.
        assertTrue(result.failure != null)

        val valid = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to " 10 ", "url" to "https://example.com/path", "enabled" to "TRUE"),
        )
        assertTrue(valid.isValid)
        assertEquals("10", valid.arguments!!.canonical["count"])
        assertEquals("true", valid.arguments!!.canonical["enabled"])
        assertTrue(valid.arguments!!.values["url"] is StrictArgumentValidator.Value.Url)
    }

    @Test
    fun rejectsUnknownAndMissingArguments() {
        val unknown = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to "1", "url" to "https://example.com", "extra" to "nope"),
        )
        assertTrue(unknown.failure is StrictArgumentValidator.Failure.UnknownArgument)

        val missing = StrictArgumentValidator.validate(spec, mapOf("count" to "1"))
        assertTrue(missing.failure is StrictArgumentValidator.Failure.MissingArgument)
    }

    @Test
    fun rejectsWrongTypesRangesAndSchemes() {
        val wrongType = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to "1.0", "url" to "https://example.com"),
        )
        assertTrue(wrongType.failure is StrictArgumentValidator.Failure.InvalidValue)

        val outOfRange = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to "11", "url" to "https://example.com"),
        )
        assertTrue(outOfRange.failure is StrictArgumentValidator.Failure.InvalidValue)

        val wrongScheme = StrictArgumentValidator.validate(
            spec,
            mapOf("count" to "1", "url" to "http://example.com"),
        )
        assertTrue(wrongScheme.failure is StrictArgumentValidator.Failure.InvalidValue)
    }

    @Test
    fun validatesDecimalNumbersWithoutTruncatingThemToIntegers() {
        val numberSpec = ToolSpec(
            id = "decimal_tool",
            capability = "decimal_tool",
            risk = ToolSpec.Risk.READ,
            privacy = ToolSpec.PrivacyBoundary.CLOUD_ALLOWED,
            requiresConfirmation = false,
            idempotent = true,
            handlerKey = "decimal_tool",
            arguments = listOf(ArgumentSpec(
                name = "ratio",
                type = ArgumentSpec.Type.NUMBER,
                required = true,
                constraints = ArgumentSpec.Constraints(minDecimal = 0.25, maxDecimal = 2.0),
            )),
        )

        val valid = StrictArgumentValidator.validate(numberSpec, mapOf("ratio" to "1.50"))
        assertTrue(valid.isValid)
        assertEquals("1.5", valid.arguments!!.canonical["ratio"])
        assertTrue(valid.arguments!!.values["ratio"] is StrictArgumentValidator.Value.Number)
        assertTrue(StrictArgumentValidator.validate(numberSpec, mapOf("ratio" to "0.2")).failure is StrictArgumentValidator.Failure.InvalidValue)
        assertTrue(StrictArgumentValidator.validate(numberSpec, mapOf("ratio" to "NaN")).failure is StrictArgumentValidator.Failure.InvalidValue)
    }
}
