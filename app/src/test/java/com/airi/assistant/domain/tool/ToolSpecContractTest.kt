package com.airi.assistant.domain.tool

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSpecContractTest {
    @Test
    fun argumentNamesRemainUniqueAndTyped() {
        val spec = ToolSpec(
            id = "calendar_read",
            capability = "calendar_read",
            risk = ToolSpec.Risk.READ,
            privacy = ToolSpec.PrivacyBoundary.CLOUD_ALLOWED,
            requiresConfirmation = false,
            idempotent = true,
            handlerKey = "calendar.read",
            arguments = listOf(
                ArgumentSpec("date", ArgumentSpec.Type.DATE, required = true),
                ArgumentSpec("limit", ArgumentSpec.Type.INTEGER, required = false,
                    constraints = ArgumentSpec.Constraints(minLength = 1, maxLength = 3)),
            ),
        )
        assertEquals(listOf("date", "limit"), spec.arguments.map { it.name })
    }
}
