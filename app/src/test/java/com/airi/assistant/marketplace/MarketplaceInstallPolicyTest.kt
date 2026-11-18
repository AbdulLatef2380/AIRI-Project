package com.airi.assistant.marketplace

import com.airi.assistant.community.SkillSecurityScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Report 3, M1. */
class MarketplaceInstallPolicyTest {

    @Test
    fun acceptsOnlyHttpsPublicHosts() {
        val ok = MarketplaceInstallPolicy.checkHttpsPublicUrl("https://skills.example.com/a/skill.json")
        assertTrue(ok is MarketplaceInstallPolicy.UrlDecision.Allowed)
        assertEquals("skills.example.com", (ok as MarketplaceInstallPolicy.UrlDecision.Allowed).host)
    }

    @Test
    fun rejectsPlainHttpInternalHostsAndCredentials() {
        listOf(
            "http://skills.example.com/skill.json",
            "https://127.0.0.1/skill.json",
            "https://192.168.1.1/skill.json",
            "https://2130706433/skill.json",
            "https://[fd00::1]/skill.json",
            "https://user:pw@skills.example.com/skill.json",
            "ftp://skills.example.com/skill.json",
            "not a url",
        ).forEach {
            assertTrue(
                "expected rejection: $it",
                MarketplaceInstallPolicy.checkHttpsPublicUrl(it) is MarketplaceInstallPolicy.UrlDecision.Rejected
            )
        }
    }

    @Test
    fun endpointHostIsNullForRejectedEndpoints() {
        assertEquals("api.example.com", MarketplaceInstallPolicy.endpointHost("https://api.example.com/run"))
        assertEquals(null, MarketplaceInstallPolicy.endpointHost("https://localhost/run"))
    }

    @Test
    fun securityScannerFlagsDangerousManifests() {
        assertTrue(SkillSecurityScanner.scan("""{"endpoint":"http://x.example/run"}""").isNotEmpty())
        assertTrue(SkillSecurityScanner.scan("""{"entry":"Runtime.getRuntime().exec(x)"}""").isNotEmpty())
        assertTrue(SkillSecurityScanner.scan("""{"endpoint":"https://x.example/run","name":"ok"}""").isEmpty())
    }

    @Test
    fun githubImporterAcceptsOnlyPublicHttpsExecutionEndpoints() {
        fun manifest(endpoint: String) = """{
            "id":"sample_skill","name":"Sample Skill","description":"A valid sample skill.",
            "version":"1.0.0","author":"Test","tools":[],"endpoint":"$endpoint"
        }"""

        assertTrue(GitHubSkillImporter.validateJsonString(manifest("https://api.example.com/run")).isValid)
        listOf(
            "http://api.example.com/run",
            "https://127.0.0.1/run",
            "https://user:secret@api.example.com/run"
        ).forEach { endpoint ->
            assertTrue(
                "expected importer to reject endpoint: $endpoint",
                !GitHubSkillImporter.validateJsonString(manifest(endpoint)).isValid
            )
        }
    }
}
