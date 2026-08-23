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
    fun missingProjectSecretFailsClosedWithoutReadingLegacyCredentialOrCallingAdapter() = runBlocking {
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

        assertEquals("project_secret_missing", (output as ConnectorOutput.Failure).code)
        assertEquals(0, legacyReads)
        assertEquals(0, adapterCalls)
    }

    @Test
    fun invalidProjectOwnershipFailsClosedWithoutLegacyFallbackOrAdapterCall() = runBlocking {
        SecretVault.storeProjectSecret(PROJECT_ID, "GITHUB_PAT", PROJECT_SECRET, "github")
        var legacyReads = 0
        var adapterCalls = 0
        val connector = GitHubConnector(
            secretVault = SecretVault,
            legacyCredentialProvider = { _, _ ->
                legacyReads++
                "fixture-legacy-credential"
            },
            projectExecutionOwnership = { false },
            tokenExecutor = { _, _ ->
                adapterCalls++
                ConnectorOutput.Success("unexpected")
            }
        )

        val output = connector.execute(projectInput())

        assertEquals("project_secret_context_rejected", (output as ConnectorOutput.Failure).code)
        assertEquals(0, legacyReads)
        assertEquals(0, adapterCalls)
    }

    @Test
    fun ownedProjectExecutionUsesProjectSecretInInjectedAdapterOnly() = runBlocking {
        SecretVault.storeProjectSecret(PROJECT_ID, "GITHUB_PAT", PROJECT_SECRET, "github")
        var legacyReads = 0
        var adapterCalls = 0
        var projectCredentialReachedAdapter = false
        val connector = GitHubConnector(
            secretVault = SecretVault,
            legacyCredentialProvider = { _, _ ->
                legacyReads++
                "fixture-legacy-credential"
            },
            projectExecutionOwnership = { execution ->
                execution.taskId == TASK_ID && execution.projectId == PROJECT_ID && execution.stepId == STEP_ID
            },
            tokenExecutor = { input, token ->
                adapterCalls++
                projectCredentialReachedAdapter = token == PROJECT_SECRET && input.action == "list_repos"
                ConnectorOutput.Success("fixture-project-adapter-result")
            }
        )

        val output = connector.execute(projectInput())

        assertTrue(output is ConnectorOutput.Success)
        assertEquals(0, legacyReads)
        assertEquals(1, adapterCalls)
        assertTrue(projectCredentialReachedAdapter)
        assertFalse((output as ConnectorOutput.Success).text.contains(PROJECT_SECRET))
    }

    private fun projectInput() = ConnectorInput(
        action = "list_repos",
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
