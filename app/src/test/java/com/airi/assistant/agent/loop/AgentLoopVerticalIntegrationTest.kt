package com.airi.assistant.agent.loop

import android.content.Context
import android.content.ContextWrapper
import com.airi.assistant.ai.QueryType
import com.airi.assistant.agent.loop.tool.ToolDispatcher
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorAgentAction
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorPermissionLevel
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.ConnectorRuntimeManager
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorToolBridge
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.connector.InMemoryConnectorAccessProfileStore
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.core.UniversalRuntimeTraceRecorder
import com.airi.assistant.core.UniversalTraceEventType
import com.airi.assistant.execution.AgentOrchestrator
import com.airi.assistant.execution.CompletionMetadata
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ExecutionFirewall
import com.airi.assistant.security.ScopedPermissionRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * End-to-end JVM coverage for the real AgentLoop -> ToolDispatcher ->
 * ConnectorToolBridge -> ConnectorRuntimeManager path.
 */
class AgentLoopVerticalIntegrationTest {
    private val testContext: Context = ContextWrapper(null)

    @Test
    fun connectorToolCallReturnsNormalizedResultAndPreservesAllIdentityCoordinates() = runBlocking {
        val connector = RecordingConnector()
        val fixture = fixture(connector)
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_call\":{\"name\":\"connector_vertical_read\",\"args\":{}}}",
            "The connector result was received.",
        )
        val result = fixture.loop(orchestrator).run(
            input = "Read the connected source",
            systemPrompt = "You are AIRI.",
            tools = listOf(fixture.readSchema),
            queryType = QueryType.UNKNOWN,
            sessionId = "session-vertical",
            requestedExecutionId = "execution-vertical",
            messageId = "message-vertical",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals("The connector result was received.", result.finalAnswer)
        assertEquals(listOf("connector_vertical_read"), result.toolsInvoked)
        assertEquals(
            "Connector tool result: " + orchestrator.requests[1].conversationHistory
                .filter { it.kind == "tool_result" }.joinToString { it.content },
            1,
            connector.executeCalls,
        )
        assertEquals("execution-vertical", connector.lastInput?.executionId)
        assertEquals("trace-execution-vertical", connector.lastInput?.traceId)
        assertEquals("session-vertical", connector.lastInput?.sessionId)
        assertEquals("message-vertical", connector.lastInput?.messageId)
        assertTrue(connector.lastInput?.actionId?.startsWith("tool_") == true)
        assertEquals(2, orchestrator.requests.size)
        assertTrue(orchestrator.requests[1].conversationHistory.any { it.kind == "tool_result" })
    }

    @Test
    fun deterministicBuiltinToolPublishesCompleteTraceLifecycle() = runBlocking {
        ExecutionStatusBus.reset()
        try {
            val orchestrator = ScriptedOrchestrator("The current time was read.")
            val loop = AgentLoop(
                orchestrator = orchestrator,
                dispatcher = ToolDispatcher(),
                appContext = ContextWrapper(null),
            )

            val result = loop.run(
                input = "What time is it right now?",
                systemPrompt = "You are AIRI.",
                tools = listOf(com.airi.assistant.agent.loop.tool.BuiltinTools.CURRENT_TIME),
                requestedExecutionId = "execution-native-lifecycle",
                onToken = {},
            )

            assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
            val trace = ExecutionStatusBus.traceSnapshot("execution-native-lifecycle")
            assertTrue(trace.any { it.kind == ExecutionTraceKind.TOOL_REQUESTED })
            assertTrue(trace.any { it.kind == ExecutionTraceKind.TOOL_STARTED })
            assertTrue(trace.any { it.kind == ExecutionTraceKind.TOOL_OUTPUT })
            assertTrue(trace.any { it.kind == ExecutionTraceKind.VERIFICATION_STARTED })
            assertTrue(trace.any { it.kind == ExecutionTraceKind.VERIFICATION_PASSED })
            assertTrue(trace.any { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
            assertEquals(
                1,
                trace.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED && it.actionId == "tool_deterministic_current_time" },
            )
        } finally {
            ExecutionStatusBus.reset()
        }
    }

    @Test
    fun invalidArgumentsStopBeforeConnectorRuntimeAndReturnStructuredErrorToNextTurn() = runBlocking {
        val connector = RecordingConnector()
        val fixture = fixture(connector)
        val schema = fixture.readSchema.copy(
            parameters = mapOf(
                "query" to ToolSchema.Param(type = "string", required = true),
            ),
        )
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_call\":{\"name\":\"connector_vertical_read\",\"args\":{}}}",
            "I could not call the tool with those arguments.",
        )

        val result = fixture.loop(orchestrator).run(
            input = "Read the source",
            systemPrompt = "You are AIRI.",
            tools = listOf(schema),
            requestedExecutionId = "execution-invalid-verification",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(0, connector.executeCalls)
        assertTrue(orchestrator.requests[1].conversationHistory.any {
            it.kind == "tool_result" && it.content.contains("invalid_argument")
        })
        val trace = ExecutionStatusBus.traceSnapshot("execution-invalid-verification")
        assertTrue(trace.any { it.kind == ExecutionTraceKind.VERIFICATION_FAILED })
        assertTrue(trace.none { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
    }

    @Test
    fun unknownToolNeverReachesDispatcherAndIsReturnedAsToolNotFound() = runBlocking {
        val connector = RecordingConnector()
        val fixture = fixture(connector)
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_call\":{\"name\":\"connector_missing_action\",\"args\":{}}}",
            "The requested action is unavailable.",
        )

        val result = fixture.loop(orchestrator).run(
            input = "Use the missing action",
            systemPrompt = "You are AIRI.",
            tools = listOf(fixture.readSchema),
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(0, connector.executeCalls)
        assertTrue(orchestrator.requests[1].conversationHistory.any {
            it.kind == "tool_result" && it.content.contains("tool_not_found")
        })
    }

    @Test
    fun duplicateReadCallsInOneBatchDispatchOnlyOnce() = runBlocking {
        ExecutionStatusBus.reset()
        try {
            val connector = RecordingConnector()
            val fixture = fixture(connector)
            val orchestrator = ScriptedOrchestrator(
                "{\"tool_calls\":[" +
                    "{\"id\":\"first\",\"name\":\"connector_vertical_read\",\"args\":{}} ," +
                    "{\"id\":\"second\",\"name\":\"connector_vertical_read\",\"args\":{}}]}",
                "Duplicate work was suppressed.",
            )

            val result = fixture.loop(orchestrator).run(
                input = "Read the source once",
                systemPrompt = "You are AIRI.",
                tools = listOf(fixture.readSchema),
                requestedExecutionId = "execution-batch-trace",
                onToken = {},
            )

            assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
            assertEquals(
                "Batch connector tool results: " + orchestrator.requests[1].conversationHistory
                    .filter { it.kind == "tool_result" }.joinToString { it.content },
                1,
                connector.executeCalls,
            )
            assertEquals(2, orchestrator.requests.size)
            assertTrue(orchestrator.requests[1].conversationHistory.count { it.kind == "tool_result" } == 2)
            val batchTrace = ExecutionStatusBus.traceSnapshot("execution-batch-trace")
            val started = batchTrace.filter { it.kind == ExecutionTraceKind.TOOL_STARTED }
            val terminal = batchTrace.filter {
                it.kind == ExecutionTraceKind.TOOL_COMPLETED || it.kind == ExecutionTraceKind.TOOL_FAILED
            }
            assertEquals(setOf("tool_1_batch_1", "tool_1_batch_2"), started.mapNotNull { it.actionId }.toSet())
            assertEquals(2, terminal.size)
            assertEquals(
                setOf(ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.TOOL_FAILED),
                terminal.map { it.kind }.toSet(),
            )
            assertTrue(started.all { it.parentId == "step_1" })
            assertTrue(terminal.all { it.durationMs != null })
        } finally {
            ExecutionStatusBus.reset()
        }
    }

    @Test
    fun connectorApprovalIsNormalizedAndRecordedAsWaitingTrace() = runBlocking {
        ExecutionStatusBus.reset()
        try {
            val connector = RecordingConnector().apply {
                output = ConnectorOutput.ApprovalRequired(
                    approvalId = "approval-vertical",
                    taskId = "task-vertical",
                    runId = "run-vertical",
                    stepId = "step-vertical",
                    expiresAtMs = 123L,
                    message = "Approval is required",
                )
            }
            val fixture = fixture(connector)
            val orchestrator = ScriptedOrchestrator(
                "{\"tool_call\":{\"name\":\"connector_vertical_read\",\"args\":{}}}",
                "Waiting state was recorded.",
            )
            val runtimeTrace = UniversalRuntimeTraceRecorder()

            val result = fixture.loop(orchestrator, runtimeTrace).run(
                input = "Read the screen with the approval-gated action",
                systemPrompt = "You are AIRI.",
                tools = listOf(fixture.readSchema),
                sessionId = "session-approval",
                requestedExecutionId = "execution-approval",
                onToken = {},
            )

            assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
            assertEquals(
                "Approval connector tool result: " + orchestrator.requests[1].conversationHistory
                    .filter { it.kind == "tool_result" }.joinToString { it.content },
                1,
                connector.executeCalls,
            )
            val approvalTrace = ExecutionStatusBus.traceSnapshot("execution-approval")
            val waiting = approvalTrace.single {
                it.kind == ExecutionTraceKind.WAITING_FOR_APPROVAL && !it.actionId.isNullOrBlank()
            }
            assertEquals("step_1", waiting.parentId)
            assertTrue(approvalTrace.none {
                it.actionId == waiting.actionId &&
                    it.kind in setOf(ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.TOOL_FAILED)
            })
            assertTrue(runtimeTrace.eventsFor("execution-approval").none {
                it.eventType == UniversalTraceEventType.CONNECTOR_EXECUTION_COMPLETED && it.outcome == "success"
            })
        } finally {
            ExecutionStatusBus.reset()
        }
    }

    @Test
    fun parallelToolCallsFailClosedWhenSandboxIsMissing() = runBlocking {
        val connector = RecordingConnector()
        val fixture = fixture(connector)
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_calls\":[" +
                "{\"id\":\"first\",\"name\":\"connector_vertical_read\",\"args\":{}} ," +
                "{\"id\":\"second\",\"name\":\"connector_vertical_read\",\"args\":{}}]}",
            "The parallel request was blocked safely.",
        )

        val result = fixture.loop(orchestrator, sandbox = null).run(
            input = "Read the source twice",
            systemPrompt = "You are AIRI.",
            tools = listOf(fixture.readSchema),
            requestedExecutionId = "execution-parallel-no-sandbox",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(0, connector.executeCalls)
        val toolResults = orchestrator.requests[1].conversationHistory.filter { it.kind == "tool_result" }
        assertEquals(2, toolResults.size)
        assertTrue(toolResults.all { it.content.startsWith("Error [") })
        assertTrue(toolResults.any { it.content.contains("Sandbox is required") })
    }

    @Test
    fun unauthenticatedConnectorIsRejectedBeforeProviderInvocation() = runBlocking {
        val connector = RecordingConnector()
        val fixture = fixture(connector, granted = false)
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_call\":{\"name\":\"connector_vertical_read\",\"args\":{}}}",
            "The connector was not authenticated.",
        )

        val result = fixture.loop(orchestrator).run(
            input = "Read the connected source",
            systemPrompt = "You are AIRI.",
            tools = listOf(fixture.readSchema),
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(0, connector.executeCalls)
        val toolResults = orchestrator.requests[1].conversationHistory.filter { it.kind == "tool_result" }
        assertTrue("Expected permission_denied, got: ${toolResults.map { it.content }}", toolResults.any {
            it.content.contains("permission_denied")
        })
    }

    @Test
    fun cancellationAtModelBoundaryProducesCancelledTerminalState() = runBlocking {
        val fixture = fixture(RecordingConnector())
        val result = fixture.loop(CancellingOrchestrator()).run(
            input = "Answer this request",
            systemPrompt = "You are AIRI.",
            tools = emptyList(),
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.CANCELLED, result.terminalState)
    }

    private fun fixture(connector: RecordingConnector, granted: Boolean = true): Fixture {
        val registry = ConnectorRegistry().apply { register(connector) }
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            if (granted) set("vertical", ConnectorAccessProfile.READ_ONLY)
        }
        val bridge = ConnectorToolBridge(registry, ConnectorRuntimeManager(registry, profiles), profiles)
        val permissions = ScopedPermissionRegistry().apply {
            grant("agent_loop", ScopedPermissionRegistry.AgentPermission.CALL_CONNECTOR_API)
        }
        return Fixture(
            dispatcher = ToolDispatcher(connectorToolBridge = bridge),
            readSchema = bridge.asToolSchemas().singleOrNull()
                ?: ToolSchema("connector_vertical_read", "Read vertical fixture"),
            sandbox = AgentSandbox(ExecutionFirewall(permissions), permissions),
        )
    }

    private data class Fixture(
        val dispatcher: ToolDispatcher,
        val readSchema: ToolSchema,
        val sandbox: AgentSandbox,
    ) {
        fun loop(
            orchestrator: AgentOrchestrator,
            runtimeTrace: UniversalRuntimeTraceRecorder? = null,
            sandbox: AgentSandbox? = this.sandbox,
        ) = AgentLoop(
            orchestrator = orchestrator,
            dispatcher = dispatcher,
            appContext = ContextWrapper(null),
            agentSandbox = sandbox,
            runtimeTrace = runtimeTrace,
        )
    }

    private class ScriptedOrchestrator(vararg responses: String) : AgentOrchestrator {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<ExecutionRequest>()

        override suspend fun executeStream(
            request: ExecutionRequest,
            context: Context,
            onToken: suspend (String) -> Unit,
            onComplete: suspend (String, Long, ExecOrigin, CompletionMetadata) -> Unit,
            onError: suspend (String, ExecOrigin) -> Unit,
        ) {
            requests += request
            onComplete(responses.removeFirst(), 1L, ExecOrigin.LOCAL, CompletionMetadata())
        }
    }

    private class CancellingOrchestrator : AgentOrchestrator {
        override suspend fun executeStream(
            request: ExecutionRequest,
            context: Context,
            onToken: suspend (String) -> Unit,
            onComplete: suspend (String, Long, ExecOrigin, CompletionMetadata) -> Unit,
            onError: suspend (String, ExecOrigin) -> Unit,
        ) {
            throw CancellationException("test cancellation")
        }
    }

    private class RecordingConnector : Connector {
        override val id = "vertical"
        override val name = "Vertical fixture"
        override val description = "Vertical integration fixture"
        override val type = ConnectorType.APP
        private val state = MutableStateFlow(ConnectorState(connected = true, healthy = true))
        var executeCalls = 0
        var lastInput: ConnectorInput? = null
        var output: ConnectorOutput = ConnectorOutput.Success("vertical-result")

        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = state
        override suspend fun connect() = state.value
        override suspend fun disconnect() { state.value = ConnectorState(false, false) }
        override fun agentActions() = listOf(
            ConnectorAgentAction("read", "Read vertical fixture", ConnectorPermissionLevel.READ),
        )
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            executeCalls++
            lastInput = input
            return output
        }
    }
}
