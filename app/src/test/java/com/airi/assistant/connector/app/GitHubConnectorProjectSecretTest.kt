package com.airi.assistant.connector.app

import com.airi.assistant.connector.ConnectorExecutionContext
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.vault.SecretVault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GitHubConnectorProjectSecretTest {

    @Before
    fun setUp() {
        SecretVault.clear()
    }

    @After
    fun tearDown() {
        SecretVault.clear()
    }

    @Test
    fun unconfiguredBrokerBlocksProjectAndLegacyPatBeforeReadingEitherCredential() = runBlocking {
        SecretVault.storeProjectSecret(PROJECT_ID, "GITHUB_PAT", PROJECT_SECRET, "github")
        var legacyReads = 0
        var adapterCalls = 0
        val connector = GitHubConnector(
            secretVault = SecretVault,
            legacyCredentialProvider = { _, _ ->
                legacyReads++
                "fixture-legacy-credential"
            },
            projectExecutionOwnership = { true },
            tokenExecutor = { _, _ ->
                adapterCalls++
                ConnectorOutput.Success("unexpected")
            }
        )

        val output = connector.execute(projectInput())

        assertEquals("github_broker_not_configured", (output as ConnectorOutput.Failure).code)
        assertEquals(0, legacyReads)
        assertEquals(0, adapterCalls)
    }

    @Test
    fun oldGlobalPatCannotMakeConnectorAppearConnected() = runBlocking {
        var legacyReads = 0
        val connector = GitHubConnector(
            legacyCredentialProvider = { _, _ ->
                legacyReads++
                "fixture-legacy-credential"
            },
        )

        val state = connector.connect()

        assertFalse(state.connected)
        assertFalse(state.healthy)
        assertTrue(state.statusLine.contains("Broker", ignoreCase = true))
        assertEquals(0, legacyReads)
    }

    @Test
    fun writeActionDoesNotQueueApprovalOrReachProviderWhenBrokerIsMissing() = runBlocking {
        var adapterCalls = 0
        val connector = GitHubConnector(
            secretVault = SecretVault,
            projectExecutionOwnership = { true },
            tokenExecutor = { _, _ ->
                adapterCalls++
                ConnectorOutput.Success("unexpected")
            }
        )

        val output = connector.execute(
            projectInput(action = "create_issue").copy(
                params = mapOf("repo" to "owner/repo", "title" to "fixture")
            )
        )

        assertEquals("github_broker_not_configured", (output as ConnectorOutput.Failure).code)
        assertEquals(0, adapterCalls)
    }

    private fun projectInput(action: String = "list_repos") = ConnectorInput(
        action = action,
        params = mapOf("limit" to "10"),
        execution = ConnectorExecutionContext(
            projectId = PROJECT_ID,
            taskId = TASK_ID,
            missionId = MISSION_ID,
            runId = RUN_ID,
            stepId = STEP_ID,
            idempotencyKey = "fixture-idempotency-key"
        )
    )

    private companion object {
        const val PROJECT_ID = "project-github-fixture"
        const val TASK_ID = "task-github-fixture"
        const val MISSION_ID = "mission-github-fixture"
        const val RUN_ID = "run-github-fixture"
        const val STEP_ID = "step-github-fixture"
        const val PROJECT_SECRET = "fixture-project-credential"
    }
}
