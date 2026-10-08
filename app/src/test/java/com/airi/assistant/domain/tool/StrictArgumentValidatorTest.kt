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
}
