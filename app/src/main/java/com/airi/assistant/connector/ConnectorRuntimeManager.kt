package com.airi.assistant.connector

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ConnectorRuntimeManager(private val registry: ConnectorRegistry) {
    private val TAG = "ConnectorRuntimeManager"

    data class InflightAction(val connectorId: String, val action: String, val startedMs: Long = System.currentTimeMillis())

    private val inflight = ConcurrentHashMap<String, InflightAction>()
    private val invocationSequence = AtomicLong(0L)
    private val inflightMutex = Mutex()
    private val _inflightActions = MutableStateFlow<List<InflightAction>>(emptyList())
    val inflightActions: StateFlow<List<InflightAction>> = _inflightActions.asStateFlow()
    private val _operationStates = MutableStateFlow<Map<Long, ConnectorOperationState>>(emptyMap())
    val operationStates: StateFlow<Map<Long, ConnectorOperationState>> = _operationStates.asStateFlow()
    suspend fun execute(connectorId: String, input: ConnectorInput, maxRetries: Int = 2, timeoutMs: Long = 20_000L): ConnectorOutput {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        require(input.action.isNotBlank()) { "connector action must not be blank" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        if (registry.isExplicitlyDisconnected(connectorId)) {
            return ConnectorOutput.Failure("not_connected", "Connector '$connectorId' was explicitly disconnected", retryable = false)
        }
        val connector = registry.get(connectorId)
            ?: return ConnectorOutput.Failure("not_found", "Connector '$connectorId' not registered")
        val operationId = invocationSequence.incrementAndGet()
        val key = "${connectorId}::${input.action}#$operationId"
        trackStart(key, InflightAction(connectorId, input.action))
        setOperationState(operationId, ConnectorOperationState.Running(operationId, connectorId, input.action))
        AgentActivityBus.emit("Executing '$connectorId' → ${input.action}", ActivityCategory.CONNECTOR)
        return try {
            withTimeout(timeoutMs) {
                if (!ensureHealthy(connector)) {
                    return@withTimeout ConnectorOutput.Failure(
                        "unhealthy",
                        "Connector '$connectorId' is not healthy after connection check",
                        retryable = true
                    )
                }
                executeWithRetry(connector, input, maxRetries.coerceIn(0, MAX_RETRIES))
            }.also { output ->
                setOperationState(operationId, when (output) {
                    is ConnectorOutput.Success, is ConnectorOutput.Streaming -> ConnectorOperationState.Success(operationId)
                    is ConnectorOutput.ApprovalRequired -> ConnectorOperationState.AwaitingApproval(operationId, output.approvalId)
                    is ConnectorOutput.Failure -> ConnectorOperationState.Failed(operationId, output.code, output.message.take(MAX_ERROR_CHARS), output.retryable)
                })
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AgentActivityBus.emit("'$connectorId' timed out after ${timeoutMs}ms", ActivityCategory.CONNECTOR, ActivitySeverity.WARN)
            // A timeout has an unknown outcome. Retrying blindly can duplicate a
            // mutation, so callers must opt into a contract-specific retry.
            setOperationState(operationId, ConnectorOperationState.TimedOut(operationId, timeoutMs))
            ConnectorOutput.Failure("timeout", "Timed out after ${timeoutMs}ms; outcome is unknown", retryable = false)
        } catch (e: CancellationException) {
            // Cancellation is a control-flow signal, never a retryable connector
            // failure. Re-throw it so ViewModel/agent cancellation reaches the
            // transport and the inflight action is cleaned up by finally.
            setOperationState(operationId, ConnectorOperationState.Cancelled(operationId))
            throw e
        } catch (e: Exception) {
            setOperationState(operationId, ConnectorOperationState.Failed(operationId, "runtime_error", (e.message ?: "Unknown error").take(MAX_ERROR_CHARS), false))
            ConnectorOutput.Failure("runtime_error", e.message ?: "Unknown error")
        } finally { trackEnd(key) }
    }

    suspend fun broadcast(type: ConnectorType, input: ConnectorInput): Map<String, ConnectorOutput> = coroutineScope {
        registry.byType(type)
            .map { connector -> async { connector.id to execute(connector.id, input) } }
            .map { deferred -> deferred.await() }
            .toMap()
    }

    private suspend fun ensureHealthy(connector: Connector): Boolean {
        val current = connector.state().value
        val checked = if (current.connected && current.healthy) current else registry.connect(connector.id)
        return checked.connected && checked.healthy
    }

    private suspend fun executeWithRetry(connector: Connector, input: ConnectorInput, maxRetries: Int): ConnectorOutput {
        var last: ConnectorOutput = ConnectorOutput.Failure("not_started", "Never executed")
        for (attempt in 0..maxRetries) {
            last = try {
                connector.execute(input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ConnectorOutput.Failure("exception", e.message ?: "Exception", retryable = true)
            }
            when {
                last is ConnectorOutput.Success   -> { AgentActivityBus.emit(" '${connector.id}' ${input.action}", ActivityCategory.CONNECTOR); return last }
                last is ConnectorOutput.Streaming -> return last
                last is ConnectorOutput.ApprovalRequired -> {
                    AgentActivityBus.emit(" '${connector.id}' is awaiting approval", ActivityCategory.CONNECTOR, ActivitySeverity.WARN)
                    return last
                }
                last is ConnectorOutput.Failure && last.retryable && attempt < maxRetries -> {
                    val backoffMs = 500L * (attempt + 1)
                    AgentActivityBus.emit("Retrying '${connector.id}' (${attempt + 2}/${maxRetries + 1})", ActivityCategory.CONNECTOR, ActivitySeverity.WARN)
                    delay(backoffMs)
                }
                else -> { AgentActivityBus.emit(" '${connector.id}' failed: ${(last as? ConnectorOutput.Failure)?.message?.take(60)}", ActivityCategory.CONNECTOR, ActivitySeverity.ERROR); return last }
            }
        }
        return last
    }

    private suspend fun trackStart(key: String, action: InflightAction) = inflightMutex.withLock {
        inflight[key] = action
        _inflightActions.value = inflight.values.toList()
    }

    private suspend fun trackEnd(key: String) = inflightMutex.withLock {
        inflight.remove(key)
        _inflightActions.value = inflight.values.toList()
    }

    private fun setOperationState(operationId: Long, state: ConnectorOperationState) {
        val next = _operationStates.value + (operationId to state)
        _operationStates.value = if (next.size <= MAX_OPERATION_HISTORY) next else
            next.toList().takeLast(MAX_OPERATION_HISTORY).toMap()
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val MAX_OPERATION_HISTORY = 100
        const val MAX_ERROR_CHARS = 240
    }
}
