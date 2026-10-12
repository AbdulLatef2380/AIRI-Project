package com.airi.assistant.ai.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPackageVerifierTest {
    private fun manifest(official: Boolean = false, signature: String? = null) = SkillManifest(
        id = "remote_skill",
        name = "Remote Skill",
        description = "A remote skill used for verification tests.",
        version = "1.0.0",
        author = "Test publisher",
        isOfficial = official,
        signature = signature,
    )

    @Test
    fun unsignedRemotePackageFailsClosed() {
        val result = SkillPackageVerifier.verify("{}", manifest())
        assertFalse(result.passed)
        assertTrue(result.errors.any { it.contains("Unsigned remote skill") })
    }

    @Test
    fun officialPackageMayOmitRemoteSignature() {
        val result = SkillPackageVerifier.verify("{}", manifest(official = true))
        assertTrue(result.passed)
    }

    @Test
    fun signedRemotePackageIsNotClaimedAuthenticWithoutTrustRoot() {
        val result = SkillPackageVerifier.verify("{}", manifest(signature = "ed25519:test"))
        assertTrue(result.passed)
        assertTrue(result.warnings.any { it.contains("trust root") })
    }
}
