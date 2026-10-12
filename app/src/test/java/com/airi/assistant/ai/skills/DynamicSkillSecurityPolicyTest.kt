package com.airi.assistant.ai.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicSkillSecurityPolicyTest {
    @Test
    fun acceptsHttpsPublicHostAndBoundedTemplate() {
        assertTrue(DynamicSkillSecurityPolicy.validateEndpoint("https://skills.example.com/run"))
        assertTrue(
            DynamicSkillSecurityPolicy.validateRequestTemplate(
                "{\"input\":\"{{input}}\"}",
                mapOf("Content-Type" to "application/json"),
            )
        )
    }

    @Test
    fun rejectsPrivateEndpointAndCredentialHeaders() {
        assertFalse(DynamicSkillSecurityPolicy.validateEndpoint("http://localhost:8080/run"))
        assertFalse(DynamicSkillSecurityPolicy.validateEndpoint("https://127.0.0.1/run"))
        assertFalse(
            DynamicSkillSecurityPolicy.validateRequestTemplate(
                "{\"input\":\"{{input}}\"}",
                mapOf("Authorization" to "Bearer raw-secret"),
            )
        )
    }

    @Test
    fun rejectsUnknownPlaceholdersAndOversizedTemplates() {
        assertFalse(
            DynamicSkillSecurityPolicy.validateRequestTemplate(
                "{\"token\":\"{{secret}}\"}",
                emptyMap(),
            )
        )
        assertFalse(
            DynamicSkillSecurityPolicy.validateRequestTemplate(
                "x".repeat(16_385),
                emptyMap(),
            )
        )
    }
}
