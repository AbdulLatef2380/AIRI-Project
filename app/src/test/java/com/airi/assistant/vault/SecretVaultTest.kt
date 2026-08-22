package com.airi.assistant.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SecretVaultTest {

    @Before
    fun setUp() {
        SecretVault.clear()
    }

    @Test
    fun brokersSecretOnlyWhenAuthorized() {
        SecretVault.storeSecret("OPENAI_API_KEY", "sk-secret-token")

        val authorizedValue = SecretVault.brokerSecret("agent-1", "OPENAI_API_KEY", authorizedByPolicy = true)
        assertEquals("sk-secret-token", authorizedValue)

        val deniedValue = SecretVault.brokerSecret("agent-1", "OPENAI_API_KEY", authorizedByPolicy = false)
        assertNull(deniedValue)
    }
}
