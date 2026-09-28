package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointPolicyTest {
    @Test
    fun acceptsHttpsForRemoteAndLocalServers() {
        assertTrue(LocalEndpointPolicy.isAllowed("https://api.example.com/v1"))
        assertTrue(LocalEndpointPolicy.isAllowed("https://192.168.1.10:11434/v1"))
    }

    @Test
    fun acceptsHttpOnlyForExplicitLocalHosts() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:11434/v1",
            "http://10.0.2.2:11434/v1",
            "http://ollama.local:11434/v1",
            "http://studio.office.local:1234/v1",
        ).forEach { assertTrue("Expected allowed: $it", LocalEndpointPolicy.isAllowed(it)) }
    }

    @Test
    fun releasePolicyRequiresHttpsEvenForLocalHosts() {
        assertFalse(LocalEndpointPolicy.isAllowed("http://ollama.local:11434/v1", allowLocalCleartext = false))
        assertFalse(LocalEndpointPolicy.isAllowed("http://localhost:11434/v1", allowLocalCleartext = false))
        assertTrue(LocalEndpointPolicy.isAllowed("https://ollama.local:11434/v1", allowLocalCleartext = false))
    }

    @Test
    fun rejectsArbitraryCleartextAndMalformedOrCredentialBearingUrls() {
        listOf(
            "http://192.168.1.10:11434/v1",
            "http://example.com/v1",
            "file:///tmp/model",
            "http://user:password@localhost:11434/v1",
            "http://localhost:11434/v1?token=secret",
            "not a url",
        ).forEach { assertFalse("Expected rejected: $it", LocalEndpointPolicy.isAllowed(it)) }
    }

    @Test
    fun normalizesTrailingSlashesWithoutChangingPath() {
        assertEquals(
            "http://ollama.local:11434/v1",
            LocalEndpointPolicy.normalizeAllowedEndpoint("  http://ollama.local:11434/v1///  "),
        )
    }
}
