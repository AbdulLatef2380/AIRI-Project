package com.airi.assistant.terminal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.agent.loop.AgentLoopExecutionContext
import com.airi.assistant.agent.sandbox.SandboxExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IsolatedTerminalServiceInstrumentationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun probeConfirmsBoundaryBeforeReleaseEnablement() = runBlocking {
        val capability = IsolatedTerminalClient(context).probe()
        assertTrue("isolated UID proof failed: ${capability.reason}", capability.uidSeparated)
        assertTrue("app-files denial proof failed: ${capability.reason}", capability.appFilesDenied)
        assertTrue("network denial proof failed: ${capability.reason}", capability.networkDenied)
        assertTrue("resource watchdog proof failed: ${capability.reason}", capability.limitsEnforced)
    }

    @Test
    fun allowedCommandReturnsEvidenceThroughBinder() = runBlocking {
        val request = TerminalExecutionRequest(
            command = "echo isolated",
            context = AgentLoopExecutionContext(
                taskId = "task-isolated",
                missionId = "mission-isolated",
                projectId = null,
                runId = "run-isolated",
                stepId = "step-isolated",
                agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
                sourceSessionId = "session-isolated",
            ),
            executionId = "execution-isolated",
            actionId = "action-isolated",
            traceId = "trace-isolated",
            messageId = "message-isolated",
        )
        val result = IsolatedTerminalClient(context).execute(request)
        assertTrue("unexpected result: $result", result is SandboxExecutor.ExecutionResult.Success)
        assertEquals("isolated\n", (result as SandboxExecutor.ExecutionResult.Success).output)
    }
}
