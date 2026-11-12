package com.airi.assistant.tools.execution

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourcePolicyTest {

    @Test
    fun permitsOnlyPublicHttpReadSources() {
        assertTrue(
            SearchSourcePolicy.evaluateRead("https://example.com/article")
                is SearchSourcePolicy.Decision.ReadAllowed
        )
        assertTrue(
            SearchSourcePolicy.evaluateRead("http://127.0.0.1/private")
                is SearchSourcePolicy.Decision.Blocked
        )
        assertTrue(
            SearchSourcePolicy.evaluateRead("file:///tmp/secret")
                is SearchSourcePolicy.Decision.Blocked
        )
    }

    @Test
    fun externalOpenRequiresUserTakeover() {
        assertTrue(
            SearchSourcePolicy.evaluateExternalOpen("https://example.com")
                is SearchSourcePolicy.Decision.RequiresUserTakeover
        )
        assertTrue(
            SearchSourcePolicy.evaluateExternalOpen("https://localhost")
                is SearchSourcePolicy.Decision.Blocked
        )
    }

    @Test
    fun blocksObfuscatedPrivateAndMetadataUrlsAndCredentials() {
        listOf(
            "https://2130706433/private", "https://0x7f000001/", "https://[::ffff:127.0.0.1]/",
            "https://[fe80::1]/", "https://169.254.169.254/latest/meta-data/",
            "https://metadata.google.internal/computeMetadata/v1/",
            "https://user:password@example.com/private", "ftp://example.com/file",
        ).forEach { url ->
            assertTrue("expected blocked: $url", SearchSourcePolicy.evaluateRead(url) is SearchSourcePolicy.Decision.Blocked)
        }
    }
}
