package com.airi.assistant.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SecretVaultTest {

    @Before
    fun setUp() {
        SecretVault.clear()
    }

    @Test
    fun brokerIssuesCapabilityInsteadOfRawSecret() {
        SecretVault.storeSecret("OPENAI_API_KEY", "sk-secret-token")

        val capability = SecretVault.brokerSecret("agent-1", "OPENAI_API_KEY", authorizedByPolicy = true)

        assertNotNull(capability)
        assertNotEquals("sk-secret-token", capability?.token)
        assertEquals("agent-1", capability?.agentId)
        assertEquals("OPENAI_API_KEY", capability?.keyName)
        assertEquals(SecretVault.CapabilityStatus.ACTIVE, SecretVault.capabilityStatus(capability!!.token))
    }

    @Test
    fun capabilityIsBoundToAgentOperationAndSingleUse() {
        SecretVault.storeSecret("OPENAI_API_KEY", "sk-secret-token")
        val capability = SecretVault.issueCapability(
            agentId = "agent-1",
            keyName = "OPENAI_API_KEY",
            operation = "provider_request",
            authorizedByPolicy = true
        )!!

        val wrongAgent = SecretVault.useCapability(
            token = capability.token,
            agentId = "agent-2",
            operation = "provider_request"
        ) { it.length }
        assertEquals(SecretVault.CapabilityStatus.DENIED, wrongAgent.status)

        val consumed = SecretVault.useCapability(
            token = capability.token,
            agentId = "agent-1",
            operation = "provider_request"
        ) { it.reversed() }
        assertEquals(SecretVault.CapabilityStatus.CONSUMED, consumed.status)
        assertEquals("nekot-terces-ks", consumed.value)
        assertNull(SecretVault.capabilityStatus(capability.token))
    }

    @Test
    fun projectSecretCannotBeUsedAcrossProjectOrConnector() {
        assertEquals(
            true,
            SecretVault.storeProjectSecret(
                projectId = "project-a",
                secretId = "GITHUB_TOKEN",
                secretValue = "project-a-token",
                connectorId = "github"
            )
        )
        val capability = SecretVault.issueCapability(
            agentId = "agent-1",
            keyName = "GITHUB_TOKEN",
            operation = "connector_request",
            authorizedByPolicy = true,
            taskId = "task-a",
            projectId = "project-a",
            connectorId = "github"
        )!!

        val wrongProject = SecretVault.useProjectCapability(
            token = capability.token,
            agentId = "agent-1",
            operation = "connector_request",
            projectId = "project-b",
            connectorId = "github"
        ) { it.length }
        assertEquals(SecretVault.CapabilityStatus.DENIED, wrongProject.status)

        val wrongConnector = SecretVault.useProjectCapability(
            token = capability.token,
            agentId = "agent-1",
            operation = "connector_request",
            projectId = "project-a",
            connectorId = "other"
        ) { it.length }
        assertEquals(SecretVault.CapabilityStatus.DENIED, wrongConnector.status)

        val consumed = SecretVault.useProjectCapability(
            token = capability.token,
            agentId = "agent-1",
            operation = "connector_request",
            projectId = "project-a",
            connectorId = "github"
        ) { it.reversed() }
        assertEquals(SecretVault.CapabilityStatus.CONSUMED, consumed.status)
        assertEquals("nekot-a-tcejorp", consumed.value)
    }

    @Test
    fun projectSecretPresenceIsScopedAndNeverReturnsSecretMaterial() {
        assertEquals(false, SecretVault.hasProjectSecret("project-a", "GITHUB_PAT", "github"))
        assertEquals(true, SecretVault.storeProjectSecret("project-a", "GITHUB_PAT", "project-a-token", "github"))

        assertEquals(true, SecretVault.hasProjectSecret("project-a", "GITHUB_PAT", "github"))
        assertEquals(false, SecretVault.hasProjectSecret("project-b", "GITHUB_PAT", "github"))
        assertEquals(false, SecretVault.hasProjectSecret("project-a", "GITHUB_PAT", "other"))

        assertEquals(true, SecretVault.revokeProjectSecret("project-a", "GITHUB_PAT", "github"))
        assertEquals(false, SecretVault.hasProjectSecret("project-a", "GITHUB_PAT", "github"))
    }

    @Test
    fun revokingProjectSecretInvalidatesOutstandingCapability() {
        SecretVault.storeProjectSecret("project-a", "API_TOKEN", "secret")
        val capability = SecretVault.issueCapability(
            agentId = "agent-1",
            keyName = "API_TOKEN",
            operation = "provider_request",
            authorizedByPolicy = true,
            projectId = "project-a"
        )!!

        assertEquals(true, SecretVault.revokeProjectSecret("project-a", "API_TOKEN"))
        val result = SecretVault.useProjectCapability(
            token = capability.token,
            agentId = "agent-1",
            operation = "provider_request",
            projectId = "project-a"
        ) { it }
        assertEquals(SecretVault.CapabilityStatus.REVOKED, result.status)
    }

    @Test
    fun unauthorizedOrExpiredRequestsDoNotExposeSecret() {
        SecretVault.storeSecret("OPENAI_API_KEY", "sk-secret-token")
        assertNull(SecretVault.brokerSecret("agent-1", "OPENAI_API_KEY", authorizedByPolicy = false))

        val capability = SecretVault.issueCapability(
            agentId = "agent-1",
            keyName = "OPENAI_API_KEY",
            operation = "provider_request",
            authorizedByPolicy = true,
            ttlMs = 10_000L
        )!!
        assertEquals(SecretVault.CapabilityStatus.ACTIVE, SecretVault.capabilityStatus(capability.token))
        assertEquals(true, SecretVault.revokeCapability(capability.token))
        assertNull(SecretVault.capabilityStatus(capability.token))
    }
}
