package com.airi.assistant.agent.loop

import android.content.Context
import android.content.ContextWrapper
import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillRoutingSource
import com.airi.assistant.ai.skills.SkillToolBridge
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.agent.loop.tool.ToolDispatcher
import com.airi.assistant.agent.loop.tool.ToolErrorCodes
import com.airi.assistant.agent.loop.tool.ToolSchema
import com.airi.assistant.agent.sandbox.SandboxExecutor
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
import com.airi.assistant.execution.CompletionReason
import com.airi.assistant.execution.CompletionMetadata
import com.airi.assistant.execution.ExecOrigin
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.terminal.TerminalAgentExecutionPort
import com.airi.assistant.terminal.TerminalExecutionGateway
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
        assertEquals(1, connector.executeCalls)
        assertEquals("execution-vertical", connector.lastInput?.executionId)
        assertEquals("trace-execution-vertical", connector.lastInput?.traceId)
        assertEquals("session-vertical", connector.lastInput?.sessionId)
        assertEquals("message-vertical", connector.lastInput?.messageId)
        assertTrue(connector.lastInput?.actionId?.startsWith("tool_") == true)
        assertEquals(2, orchestrator.requests.size)
        assertTrue(orchestrator.requests[1].conversationHistory.any { it.kind == "tool_result" })
        val lifecycle = ExecutionStatusBus.traceSnapshot("execution-vertical").filter {
            it.kind in setOf(
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.TOOL_COMPLETED,
                ExecutionTraceKind.TOOL_FAILED,
                ExecutionTraceKind.TOOL_CANCELLED,
            )
        }
        assertEquals(2, lifecycle.size)
        assertEquals(1, lifecycle.count { it.kind == ExecutionTraceKind.TOOL_STARTED })
        assertEquals(1, lifecycle.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
        assertEquals(lifecycle[0].actionId, lifecycle[1].actionId)
        assertTrue(lifecycle.all { it.executionId == "execution-vertical" && it.actionId.isNotBlank() })
        assertTrue(ExecutionStatusBus.traceSnapshot("execution-vertical").any {
            it.kind == ExecutionTraceKind.COMPLETED
        })
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
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(0, connector.executeCalls)
        assertTrue(orchestrator.requests[1].conversationHistory.any {
            it.kind == "tool_result" && it.content.contains("invalid_argument")
        })
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
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(1, connector.executeCalls)
        assertEquals(2, orchestrator.requests.size)
        assertTrue(orchestrator.requests[1].conversationHistory.count { it.kind == "tool_result" } == 2)
    }

    @Test
    fun connectorApprovalIsNormalizedAndRecordedAsWaitingTrace() = runBlocking {
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

        val result = fixture.loop(orchestrator).run(
            input = "Read the screen with the approval-gated action",
            systemPrompt = "You are AIRI.",
            tools = listOf(fixture.readSchema),
            sessionId = "session-approval",
            requestedExecutionId = "execution-approval",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals(1, connector.executeCalls)
        assertTrue(ExecutionStatusBus.traceSnapshot("execution-approval").any {
            it.kind == ExecutionTraceKind.WAITING_FOR_APPROVAL && it.actionId.isNotBlank()
        })
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
        assertTrue(orchestrator.requests[1].conversationHistory.any {
            it.kind == "tool_result" && it.content.contains("permission_denied")
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

    @Test
    fun skillToolCallReturnsResultToNextModelTurnAndRecordsOwnedLifecycle() = runBlocking {
        val skill = RecordingSkill()
        val context = SafeApplicationContext()
        val bridge = SkillToolBridge(
            context = context,
            registry = SkillRoutingSource { listOf(skill) },
            skillCtx = { SkillContext(sessionId = "session-skill-vertical") },
            readinessCtx = { SkillContext(sessionId = "session-skill-vertical") },
        )
        val orchestrator = ScriptedOrchestrator(
            "{\"tool_call\":{\"name\":\"skill_vertical_skill_read\",\"args\":{\"query\":\"safe fixture query\"}}}",
            "The skill result was received.",
        )
        val loop = AgentLoop(
            orchestrator = orchestrator,
            dispatcher = ToolDispatcher(skillToolBridge = bridge),
            appContext = context,
        )

        val result = loop.run(
            input = "Use the vertical skill",
            systemPrompt = "You are AIRI.",
            tools = bridge.asToolSchemas(),
            sessionId = "session-skill-vertical",
            requestedExecutionId = "execution-skill-vertical",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals("The skill result was received.", result.finalAnswer)
        assertEquals(listOf("skill_vertical_skill_read"), result.toolsInvoked)
        assertEquals(1, skill.executeCalls)
        assertEquals("safe fixture query", skill.lastQuery)
        assertEquals("execution-skill-vertical", skill.lastContext?.executionId)
        assertEquals("session-skill-vertical", skill.lastContext?.sessionId)
        assertEquals(2, orchestrator.requests.size)
        assertTrue(orchestrator.requests[1].conversationHistory.any {
            it.kind == "tool_result" && it.content.contains("skill_result_safe")
        })

        val lifecycle = ExecutionStatusBus.traceSnapshot("execution-skill-vertical").filter {
            it.kind in setOf(
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.TOOL_COMPLETED,
                ExecutionTraceKind.TOOL_FAILED,
                ExecutionTraceKind.TOOL_CANCELLED,
            )
        }
        assertEquals(2, lifecycle.size)
        assertEquals(1, lifecycle.count { it.kind == ExecutionTraceKind.TOOL_STARTED })
        assertEquals(1, lifecycle.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
        assertEquals(lifecycle[0].actionId, lifecycle[1].actionId)
        assertTrue(lifecycle.all {
            it.executionId == "execution-skill-vertical" && it.actionId.isNotBlank()
        })
        assertTrue(ExecutionStatusBus.traceSnapshot("execution-skill-vertical").any {
            it.kind == ExecutionTraceKind.COMPLETED
        })
    }

    @Test
    fun terminalToolRoutesThroughGatewayAndMapsDeniedOutcomeFailClosed() = runBlocking {
        var observedCommand: String? = null
        var observedAgentId: String? = null
        val successDispatcher = ToolDispatcher(
            terminalAgentExecutionPort = TerminalAgentExecutionPort { command, agentId ->
                observedCommand = command
                observedAgentId = agentId
                TerminalExecutionGateway.Result.Completed(
                    SandboxExecutor.ExecutionResult.Success("bounded terminal result"),
                )
            },
        )

        val result = successDispatcher.execute(
            toolName = "terminal_execute",
            args = mapOf("command" to "echo safe"),
            context = ContextWrapper(null),
            executionId = "execution-terminal-vertical",
            agentId = "agent_loop",
            actionId = "terminal-action-1",
        )
        assertTrue(result is ToolDispatcher.ToolResult.Success)
        assertEquals("bounded terminal result", (result as ToolDispatcher.ToolResult.Success).output)
        assertEquals("echo safe", observedCommand)
        assertEquals("agent_loop", observedAgentId)

        val deniedDispatcher = ToolDispatcher(
            terminalAgentExecutionPort = TerminalAgentExecutionPort { _, _ ->
                TerminalExecutionGateway.Result.Denied("Terminal governance denied the request")
            },
        )
        val denied = deniedDispatcher.execute(
            toolName = "terminal_execute",
            args = mapOf("command" to "cat /private/file"),
            context = ContextWrapper(null),
            executionId = "execution-terminal-denied",
            agentId = "agent_loop",
            actionId = "terminal-action-2",
        )
        assertTrue(denied is ToolDispatcher.ToolResult.Error)
        assertEquals(ToolErrorCodes.PERMISSION_DENIED, (denied as ToolDispatcher.ToolResult.Error).code)
    }

    @Test
    fun modelResponseTraceCorrelatesRequestedAndResolvedTargetWithDuration() = runBlocking {
        val recorder = UniversalRuntimeTraceRecorder()
        val fixture = fixture(RecordingConnector())
        val orchestrator = ScriptedOrchestrator(
            "Bearer response-secret user@example.com answer",
        ).apply {
            completionMetadata = CompletionMetadata(
                reason = CompletionReason.STOP,
                providerId = "local",
                requestedModelId = "cloud-model",
                requestedProviderId = "cloud-provider",
                resolvedModelId = "local-model",
                resolvedProviderId = "local",
                fallbackReason = "cloud endpoint unavailable",
            )
        }

        val result = fixture.loop(orchestrator, recorder).run(
            input = "Answer without exposing trace payload",
            systemPrompt = "You are AIRI.",
            tools = emptyList(),
            modelId = "cloud-model",
            providerId = "cloud-provider",
            sessionId = "session-model-trace",
            requestedExecutionId = "execution-model-trace",
            onToken = {},
        )

        assertEquals(AgentLoop.TerminalState.SUCCESS, result.terminalState)
        assertEquals("Bearer response-secret user@example.com answer", result.finalAnswer)
        val events = recorder.eventsFor("execution-model-trace")
        val request = events.single { it.eventType == UniversalTraceEventType.MODEL_REQUEST_BUILT }
        val response = events.single { it.eventType == UniversalTraceEventType.MODEL_RESPONSE_RECEIVED }

        assertEquals(request.executionId, response.executionId)
        assertEquals("session-model-trace", response.sessionId)
        assertTrue(request.sequence < response.sequence)
        assertEquals("cloud-model", response.attributes["requestedModel"])
        assertEquals("cloud-provider", response.attributes["requestedProvider"])
        assertEquals("local-model", response.attributes["resolvedModel"])
        assertEquals("local", response.attributes["resolvedProvider"])
        assertEquals("cloud endpoint unavailable", response.attributes["fallbackReason"])
        assertTrue(response.attributes.getValue("durationMs").toLong() >= 0L)
        assertEquals("agent_loop_call_llm_wall_clock", response.attributes["durationScope"])
        val traceText = response.attributes.values.joinToString("\n")
        assertTrue(traceText.contains("[REDACTED]"))
        assertTrue(traceText.contains("[EMAIL]"))
        assertTrue(!traceText.contains("response-secret"))
        assertTrue(!traceText.contains("user@example.com"))
    }

    private fun fixture(connector: RecordingConnector, granted: Boolean = true): Fixture {
        val registry = ConnectorRegistry().apply { register(connector) }
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            if (granted) set("vertical", ConnectorAccessProfile.READ_ONLY)
        }
        val bridge = ConnectorToolBridge(registry, ConnectorRuntimeManager(registry, profiles), profiles)
        return Fixture(
            dispatcher = ToolDispatcher(connectorToolBridge = bridge),
            readSchema = bridge.asToolSchemas().singleOrNull()
                ?: ToolSchema("connector_vertical_read", "Read vertical fixture"),
        )
    }

    private data class Fixture(
        val dispatcher: ToolDispatcher,
        val readSchema: ToolSchema,
    ) {
        fun loop(
            orchestrator: AgentOrchestrator,
            runtimeTrace: UniversalRuntimeTraceRecorder? = null,
        ) = AgentLoop(
            orchestrator = orchestrator,
            dispatcher = dispatcher,
            appContext = ContextWrapper(null),
            runtimeTrace = runtimeTrace,
        )
    }

    private class ScriptedOrchestrator(vararg responses: String) : AgentOrchestrator {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<ExecutionRequest>()
        var completionMetadata: CompletionMetadata = CompletionMetadata()

        override suspend fun executeStream(
            request: ExecutionRequest,
            context: Context,
            onToken: suspend (String) -> Unit,
            onComplete: suspend (String, Long, ExecOrigin, CompletionMetadata) -> Unit,
            onError: suspend (String, ExecOrigin) -> Unit,
        ) {
            requests += request
            onComplete(responses.removeFirst(), 1L, ExecOrigin.LOCAL, completionMetadata)
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

    private class RecordingSkill : AiriSkill {
        override val skillId = "vertical_skill"
        override val name = "Vertical skill"
        override val description = "Deterministic skill integration fixture"
        override val toolDefinitions = listOf(
            SkillToolDefinition(
                name = "read",
                description = "Read the fixture",
                parameters = mapOf("query" to SkillParamDef(type = "string", required = true)),
            ),
        )
        var executeCalls = 0
        var lastQuery: String? = null
        var lastContext: SkillContext? = null

        override fun score(input: String, context: SkillContext): Int = 100

        override suspend fun execute(params: Map<String, Any>): SkillResult {
            executeCalls++
            lastQuery = params["query"] as? String
            lastContext = params["context"] as? SkillContext
            return SkillResult(success = true, data = "skill_result_safe", skillName = skillId)
        }
    }

    private class SafeApplicationContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
