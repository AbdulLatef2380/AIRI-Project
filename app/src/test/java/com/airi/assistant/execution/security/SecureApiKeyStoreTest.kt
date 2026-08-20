package com.airi.assistant.execution.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureApiKeyStoreTest {

    @Test
    fun customEndpointStorageIdIsStableAndDoesNotExposeEndpointText() {
        val endpoint = "https://models.example.test/v1"

        val first = SecureApiKeyStore.customEndpointStorageId(endpoint)
        val second = SecureApiKeyStore.customEndpointStorageId(endpoint)

        assertEquals(first, second)
        assertTrue(first.startsWith("custom_endpoint_"))
        assertFalse(first.contains(endpoint))
        assertFalse(first.contains("models.example.test"))
    }

    @Test
    fun customEndpointStorageIdsAreIsolatedBetweenEndpoints() {
        val first = SecureApiKeyStore.customEndpointStorageId("https://one.example.test/v1")
        val second = SecureApiKeyStore.customEndpointStorageId("https://two.example.test/v1")

        assertNotEquals(first, second)
    }
}
