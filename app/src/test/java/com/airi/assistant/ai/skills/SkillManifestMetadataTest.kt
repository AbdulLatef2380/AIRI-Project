package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestMetadataTest {
    @Test
    fun metadataSurvivesJsonRoundTrip() {
        val manifest = SkillManifest(
            id = "secure_review",
            name = "Secure Review",
            description = "Reviews source code for defensive security issues.",
            version = "1.0.0",
            author = "AIRI",
            displayName = "Secure Review",
            inputSchema = mapOf("repository" to "string"),
            outputSchema = mapOf("report" to "string"),
            instructions = "Review only authorized code.",
            examples = listOf("Review this repository"),
            limitations = listOf("No offensive actions"),
            riskLevel = SkillRiskLevel.MEDIUM,
            requiresConfirmation = true,
            supportsStreaming = true,
            supportsAttachments = true
        )

        val restored = try {
            SkillManifest.fromJson(manifest.toJson())
        } catch (error: Throwable) {
            println("SkillManifestMetadataTest round-trip failed: ${error::class.java.name}: ${error.message}")
            error.printStackTrace()
            throw error
        }
        assertEquals(manifest.displayName, restored.displayName)
        assertEquals(manifest.inputSchema, restored.inputSchema)
        assertEquals(manifest.outputSchema, restored.outputSchema)
        assertEquals(manifest.instructions, restored.instructions)
        assertEquals(manifest.examples, restored.examples)
        assertEquals(manifest.limitations, restored.limitations)
        assertEquals(manifest.riskLevel, restored.riskLevel)
        assertTrue(restored.requiresConfirmation)
        assertTrue(restored.supportsStreaming)
        assertTrue(restored.supportsAttachments)
    }
}
