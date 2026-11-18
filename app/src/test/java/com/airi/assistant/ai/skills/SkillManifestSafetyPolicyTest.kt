package com.airi.assistant.ai.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillManifestSafetyPolicyTest {
    @Test
    fun confirmationBlocksWithoutTrustedApprovalButNotWhenNotRequired() {
        assertTrue(SkillInvocationApprovalPolicy.mustBlock(requiresConfirmation = true, toolIsDangerous = false, trustedApprovalAvailable = false))
        assertFalse(SkillInvocationApprovalPolicy.mustBlock(requiresConfirmation = false, toolIsDangerous = false, trustedApprovalAvailable = false))
        assertTrue(SkillInvocationApprovalPolicy.mustBlock(requiresConfirmation = false, toolIsDangerous = true, trustedApprovalAvailable = false))
        assertFalse(SkillInvocationApprovalPolicy.mustBlock(requiresConfirmation = true, toolIsDangerous = true, trustedApprovalAvailable = true))
    }

    @Test
    fun secretConfigurationRequiresSecureStorage() {
        val base = SkillManifest(
            id = "safe_skill", name = "Safe skill", description = "A safe test skill",
            version = "1.0.0", author = "test"
        )
        assertFalse(base.requiresSecureConfiguration())
        val secret = base.copy(configuration = mapOf(
            "api_token" to SkillManifest.ConfigField(type = "string", label = "API token", secret = true)
        ))
        assertTrue(secret.requiresSecureConfiguration())
    }
}
