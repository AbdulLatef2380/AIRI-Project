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
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ConnectorRuntimeManager(
    private val registry: ConnectorRegistry,
    private val accessProfiles: ConnectorAccessProfileStore = InMemoryConnectorAccessProfileStore(),
) {
    private val TAG = "ConnectorRuntimeManager"

    data class InflightAction(val connectorId: String, val action: String, val startedMs: Long = System.currentTimeMillis())

    private val inflight = ConcurrentHashMap<String, InflightAction>()
    private val invocationSequence = AtomicLong(0L)
    private val inflightMutex = Mutex()
    private val _inflightActions = MutableStateFlow<List<InflightAction>>(emptyList())
    val inflightActions: StateFlow<List<InflightAction>> = _inflightActions.asStateFlow()
    private val _operationStates = MutableStateFlow<Map<Long, ConnectorOperationState>>(emptyMap())
    val operationStates: StateFlow<Map<Long, ConnectorOperationState>> = _operationStates.asStateFlow()
    suspend fun execute(connectorId: String, input: ConnectorInput, maxRetries: Int = 2, timeoutMs: Long = 20_000L): ConnectorOutput =
        executeInternal(connectorId, input, maxRetries, timeoutMs, approvedContinuation = false)

    /** Only the durable approval-resume path may request this; the adapter must validate its claimed continuation. */
    internal suspend fun executeApprovedContinuation(
        connectorId: String,
        input: ConnectorInput,
        maxRetries: Int = 0,
        timeoutMs: Long = 20_000L,
    ): ConnectorOutput = executeInternal(connectorId, input, maxRetries, timeoutMs, approvedContinuation = true)

    private suspend fun executeInternal(
        connectorId: String,
        input: ConnectorInput,
        maxRetries: Int,
        timeoutMs: Long,
        approvedContinuation: Boolean,
    ): ConnectorOutput {
        require(connectorId.isNotBlank()) { "connectorId must not be blank" }
        require(input.action.isNotBlank()) { "connector action must not be blank" }
        require(timeoutMs > 0L) { "timeoutMs must be positive" }
        if (registry.isExplicitlyDisconnected(connectorId)) {
            return ConnectorOutput.Failure("not_connected", "Connector '$connectorId' was explicitly disconnected", retryable = false)
        }
        var lifecycleToken = registry.lifecycleToken(connectorId)
        val connector = registry.get(connectorId)
            ?: return ConnectorOutput.Failure("not_found", "Connector '$connectorId' not registered")
        val declaredActions = connector.agentActions()
        val governedAction = if (input.authorizationActionId != null) {
            declaredActions.firstOrNull { it.id == input.authorizationActionId }
                ?: return ConnectorOutput.Failure("permission_denied", "The requested authorization action is not declared")
        } else {
            val matching = declaredActions.filter { it.runtimeAction == input.action }
            if (matching.size > 1) {
                return ConnectorOutput.Failure("permission_denied", "An explicit authorization action is required for this connector call")
            }
            matching.singleOrNull()
        } ?: return ConnectorOutput.Failure(
            "undeclared_action", "The requested connector action has no authorization declaration and was not executed"
        )
        if (governedAction.runtimeAction != input.action) {
            return ConnectorOutput.Failure("permission_denied", "The requested action does not match its authorization declaration")
        }
        when (ConnectorAccessPolicy.evaluate(
            accessProfiles.get(governedAction.surfaceId ?: connectorId), governedAction
        )) {
            ConnectorAccessDecision.ALLOWED -> Unit
            ConnectorAccessDecision.NOT_GRANTED -> return ConnectorOutput.Failure(
                "permission_denied", "No matching user access profile is granted for this connector action"
            )
            ConnectorAccessDecision.CONFIRMATION_REQUIRED -> {
                val execution = input.execution
                val mayResume = approvedContinuation && governedAction.supportsApprovedContinuation &&
                    input.authorizationActionId == governedAction.id && execution != null &&
                    execution.isComplete && !execution.continuationId.isNullOrBlank()
                if (!mayResume) return ConnectorOutput.Failure(
                    "approval_required", "This action requires a typed approval flow and was not executed"
                )
            }
        }
        validateInput(governedAction, input)?.let { (code, message) ->
            return ConnectorOutput.Failure(code, message, retryable = false)
        }
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
                // A legitimate health reconnect advances the registry
                // generation. Capture that new token before the race check;
                // a disconnect after this point still changes it and is
                // rejected below.
                lifecycleToken = registry.lifecycleToken(connectorId)
                if (!registry.isExecutionAllowed(connectorId, lifecycleToken)) {
                    return@withTimeout ConnectorOutput.Failure(
                        "not_connected",
                        "Connector '$connectorId' changed lifecycle before execution",
                        retryable = false
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

    /** Reject undeclared, malformed, or oversized input before connecting or invoking an adapter. */
    private fun validateInput(
        action: ConnectorAgentAction,
        input: ConnectorInput,
    ): Pair<String, String>? {
        if (input.text.isNotEmpty() && action.maxTextChars <= 0) {
            return "invalid_text" to "This connector action does not accept free-form text"
        }
        if (input.text.length > action.maxTextChars) {
            return "invalid_text" to "Text payload exceeds the ${action.maxTextChars}-character action limit"
        }
        if (action.textRequired && input.text.isBlank()) {
            return "invalid_text" to "This connector action requires a non-empty text payload"
        }
        val acceptedKeys = action.parameters.keys + action.fixedParams.keys
        val unexpectedKeys = input.params.keys - acceptedKeys
        if (unexpectedKeys.isNotEmpty()) {
            return "invalid_params" to "Undeclared parameter(s): ${unexpectedKeys.sorted().joinToString()}"
        }
        for ((key, fixedValue) in action.fixedParams) {
            if (input.params[key] != fixedValue) {
                return "invalid_params" to "Fixed connector parameter '$key' does not match its declaration"
            }
        }
        for ((key, declaration) in action.parameters) {
            val value = input.params[key]
            if (value == null) {
                if (declaration.required) return "invalid_params" to "Required parameter '$key' is missing"
                continue
            }
            if (declaration.required && value.isBlank()) {
                return "invalid_params" to "Required parameter '$key' must not be blank"
            }
            if (!matchesParameterType(value, declaration)) {
                return "invalid_params" to "Parameter '$key' does not match declared type '${declaration.type}' or its bounds"
            }
        }
        val binary = input.binary
        if (binary == null) {
            if (action.binaryRequired) return "invalid_binary" to "This connector action requires a binary payload"
        } else {
            if (action.maxBinaryBytes <= 0) {
                return "invalid_binary" to "This connector action does not accept binary payloads"
            }
            if (binary.isEmpty() || binary.size > action.maxBinaryBytes) {
                return "invalid_binary" to "Binary payload must be between 1 and ${action.maxBinaryBytes} bytes"
            }
        }
        return null
    }

    private fun matchesParameterType(value: String, declaration: ConnectorAgentParameter): Boolean =
        when (declaration.type.lowercase()) {
            "string", "text" -> declaration.maxLength?.let { value.length <= it } ?: true
            "int", "integer" -> value.toLongOrNull()?.let { number ->
                (declaration.minInt == null || number >= declaration.minInt) &&
                    (declaration.maxInt == null || number <= declaration.maxInt)
            } ?: false
            "number" -> value.toDoubleOrNull()?.let { number ->
                number.isFinite() &&
                    (declaration.minNumber == null || number >= declaration.minNumber) &&
                    (declaration.maxNumber == null || number <= declaration.maxNumber)
            } ?: false
            "boolean" -> value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true)
            "object" -> runCatching { JSONObject(value) }.isSuccess
            "array" -> runCatching { JSONArray(value) }.isSuccess
            else -> false
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
