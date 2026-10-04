package com.airi.assistant.connector

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRuntimeManagerTest {

    @Test
    fun executesOnlyAfterHealthCheckReconnectsConnector() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(id = "notion", initiallyHealthy = false)
        registry.register(connector)

        val result = runtime(registry).execute("notion", ConnectorInput(action = "read"))

        assertTrue(result is ConnectorOutput.Success)
        assertEquals(1, connector.connectCalls)
        assertEquals(1, connector.executeCalls)
    }

    @Test
    fun approvalRequiredNeverRetriesConnectorAction() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = ApprovalConnector()
        registry.register(connector)

        val result = runtime(registry).execute("approval", ConnectorInput(action = "read"), maxRetries = 3)

        assertTrue(result is ConnectorOutput.ApprovalRequired)
        assertEquals(1, connector.executeCalls)
    }

    @Test
    fun broadcastAwaitsEveryConnectorResult() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(FakeConnector(id = "fast", initiallyHealthy = true, executionDelayMs = 0))
        registry.register(FakeConnector(id = "slow", initiallyHealthy = true, executionDelayMs = 650))

        val results = runtime(registry).broadcast(ConnectorType.MCP, ConnectorInput(action = "read"))

        assertEquals(setOf("fast", "slow"), results.keys)
        assertTrue(results.values.all { it is ConnectorOutput.Success })
    }

    @Test
    fun timeoutIsNotProjectedAsSuccessAndStateIsTimedOut() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(FakeConnector(id = "slow-timeout", initiallyHealthy = true, executionDelayMs = 200))

        val manager = runtime(registry)
        val result = manager.execute("slow-timeout", ConnectorInput(action = "read"), maxRetries = 0, timeoutMs = 10)

        assertTrue(result is ConnectorOutput.Failure)
        assertEquals("timeout", (result as ConnectorOutput.Failure).code)
        assertTrue(manager.operationStates.value.values.last() is ConnectorOperationState.TimedOut)
    }

    @Test
    fun explicitDisconnectBlocksLateExecutionUntilLifecycleReconnect() = runBlocking {
        val registry = ConnectorRegistry()
        registry.register(FakeConnector(id = "lifecycle", initiallyHealthy = true))
        val manager = runtime(registry)

        assertTrue(registry.disconnect("lifecycle"))
        val blocked = manager.execute("lifecycle", ConnectorInput(action = "read"))
        assertEquals("not_connected", (blocked as ConnectorOutput.Failure).code)

        val reconnected = registry.connect("lifecycle")
        assertTrue(reconnected.connected && reconnected.healthy)
        assertTrue(manager.execute("lifecycle", ConnectorInput(action = "read")) is ConnectorOutput.Success)
    }

    @Test
    fun unregisteredActionFailsClosedWithoutCallingConnector() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(id = "closed", initiallyHealthy = true, declareRead = false)
        registry.register(connector)
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            set("closed", ConnectorAccessProfile.FULL_ACCESS)
        }

        val result = ConnectorRuntimeManager(registry, profiles)
            .execute("closed", ConnectorInput(action = "delete_everything")) as ConnectorOutput.Failure

        assertEquals("undeclared_action", result.code)
        assertEquals(0, connector.executeCalls)
    }

    @Test
    fun writeActionCannotExecuteThroughOrdinaryRuntimeEvenWithFullAccess() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(id = "write-only", initiallyHealthy = true, declareWrite = true)
        registry.register(connector)
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            set("write-only", ConnectorAccessProfile.FULL_ACCESS)
        }

        val result = ConnectorRuntimeManager(registry, profiles)
            .execute("write-only", ConnectorInput(action = "write")) as ConnectorOutput.Failure

        assertEquals("approval_required", result.code)
        assertEquals(0, connector.executeCalls)
    }

    @Test
    fun undeclaredAndMissingParametersAreRejectedBeforeAdapterInvocation() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(
            id = "params",
            initiallyHealthy = true,
            extraActions = listOf(ConnectorAgentAction(
                id = "find",
                description = "Find by required query",
                parameters = mapOf("query" to ConnectorAgentParameter(required = true, maxLength = 32)),
            )),
        )
        registry.register(connector)
        val manager = runtime(registry)

        val unknown = manager.execute("params", ConnectorInput("read", params = mapOf("surprise" to "x"))) as ConnectorOutput.Failure
        val missing = manager.execute("params", ConnectorInput("find")) as ConnectorOutput.Failure

        assertEquals("invalid_params", unknown.code)
        assertEquals("invalid_params", missing.code)
        assertEquals(0, connector.executeCalls)
    }

    @Test
    fun parameterTypesAndDeclaredNumericBoundsAreEnforced() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(
            id = "bounded",
            initiallyHealthy = true,
            extraActions = listOf(ConnectorAgentAction(
                id = "page",
                description = "Read a bounded page",
                parameters = mapOf("limit" to ConnectorAgentParameter(
                    type = "integer", minInt = 1, maxInt = 3,
                )),
            )),
        )
        registry.register(connector)
        val manager = runtime(registry)

        val malformed = manager.execute("bounded", ConnectorInput("page", params = mapOf("limit" to "NaN"))) as ConnectorOutput.Failure
        val outOfRange = manager.execute("bounded", ConnectorInput("page", params = mapOf("limit" to "4"))) as ConnectorOutput.Failure
        val valid = manager.execute("bounded", ConnectorInput("page", params = mapOf("limit" to "2")))

        assertEquals("invalid_params", malformed.code)
        assertEquals("invalid_params", outOfRange.code)
        assertTrue(valid is ConnectorOutput.Success)
        assertEquals(1, connector.executeCalls)
    }

    @Test
    fun fixedParametersCannotBeSpoofedAndBinaryRequiresAnExplicitBound() = runBlocking {
        val registry = ConnectorRegistry()
        val connector = FakeConnector(
            id = "payloads",
            initiallyHealthy = true,
            extraActions = listOf(
                ConnectorAgentAction(
                    id = "mcp_search",
                    description = "Search a fixed MCP tool",
                    runtimeAction = "invoke_tool",
                    fixedParams = mapOf("tool" to "search"),
                    parameters = mapOf("query" to ConnectorAgentParameter(required = true)),
                ),
                ConnectorAgentAction(
                    id = "transcribe",
                    description = "Accept a bounded payload",
                    binaryRequired = true,
                    maxBinaryBytes = 4,
                ),
            ),
        )
        registry.register(connector)
        val manager = runtime(registry)

        val forged = manager.execute(
            "payloads",
            ConnectorInput(
                action = "invoke_tool",
                authorizationActionId = "mcp_search",
                params = mapOf("tool" to "delete_all", "query" to "x"),
            ),
        ) as ConnectorOutput.Failure
        val undeclaredBinary = manager.execute("payloads", ConnectorInput("read", binary = byteArrayOf(1))) as ConnectorOutput.Failure
        val oversized = manager.execute("payloads", ConnectorInput("transcribe", binary = ByteArray(5))) as ConnectorOutput.Failure
        val accepted = manager.execute("payloads", ConnectorInput("transcribe", binary = byteArrayOf(1, 2, 3, 4)))

        assertEquals("invalid_params", forged.code)
        assertEquals("invalid_binary", undeclaredBinary.code)
        assertEquals("invalid_binary", oversized.code)
        assertTrue(accepted is ConnectorOutput.Success)
        assertEquals(1, connector.executeCalls)
    }

    private fun runtime(registry: ConnectorRegistry): ConnectorRuntimeManager {
        val profiles = InMemoryConnectorAccessProfileStore().apply {
            registry.all().forEach { set(it.id, ConnectorAccessProfile.READ_ONLY) }
        }
        return ConnectorRuntimeManager(registry, profiles)
    }

    private class ApprovalConnector : Connector {
        override val id = "approval"
        override val name = "Approval connector"
        override val description = "Returns an approval gate"
        override val type = ConnectorType.APP
        private val stateFlow = MutableStateFlow(ConnectorState(connected = true, healthy = true))
        var executeCalls = 0

        override fun meta() = ConnectorMeta(id, name, description, type)
        override fun state() = stateFlow
        override suspend fun connect() = stateFlow.value
        override suspend fun disconnect() { stateFlow.value = ConnectorState(false, false) }
        override fun agentActions() = listOf(ConnectorAgentAction(id = "read", description = "Read test action"))
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            executeCalls++
            return ConnectorOutput.ApprovalRequired(
                approvalId = "approval-1",
                taskId = "task-1",
                runId = "run-1",
                stepId = "step-1",
                expiresAtMs = 10_000L,
                message = "Approval required"
            )
        }
    }

    private class FakeConnector(
        override val id: String,
        initiallyHealthy: Boolean,
        private val executionDelayMs: Long = 0L,
        private val declareRead: Boolean = true,
        private val declareWrite: Boolean = false,
        private val extraActions: List<ConnectorAgentAction> = emptyList(),
    ) : Connector {
        override val name: String = id
        override val description: String = "Test connector"
        override val type: ConnectorType = ConnectorType.MCP
        private val stateFlow = MutableStateFlow(
            ConnectorState(connected = initiallyHealthy, healthy = initiallyHealthy)
        )
        var connectCalls = 0
        var executeCalls = 0

        override fun meta(): ConnectorMeta = ConnectorMeta(id, name, description, type)
        override fun state() = stateFlow
        override suspend fun connect(): ConnectorState {
            connectCalls++
            stateFlow.value = ConnectorState(connected = true, healthy = true)
            return stateFlow.value
        }
        override suspend fun disconnect() {
            stateFlow.value = ConnectorState(connected = false, healthy = false)
        }
        override fun agentActions(): List<ConnectorAgentAction> = buildList {
            if (declareRead) add(ConnectorAgentAction(id = "read", description = "Read test action"))
            if (declareWrite) add(ConnectorAgentAction(
                id = "write",
                description = "Write test action",
                permission = ConnectorPermissionLevel.WRITE,
            ))
            addAll(extraActions)
        }
        override suspend fun execute(input: ConnectorInput): ConnectorOutput {
            executeCalls++
            if (executionDelayMs > 0) delay(executionDelayMs)
            return ConnectorOutput.Success("${id}:${input.action}")
        }
    }
}
