package com.airi.assistant.connector

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

class ConnectorRuntimeManager(private val registry: ConnectorRegistry) {
    private val TAG = "ConnectorRuntimeManager"

    data class InflightAction(val connectorId: String, val action: String, val startedMs: Long = System.currentTimeMillis())

    private val inflight = ConcurrentHashMap<String, InflightAction>()
    private val _inflightActions = MutableStateFlow<List<InflightAction>>(emptyList())
    val inflightActions: StateFlow<List<InflightAction>> = _inflightActions.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun execute(connectorId: String, input: ConnectorInput, maxRetries: Int = 2, timeoutMs: Long = 20_000L): ConnectorOutput {
        val connector = registry.get(connectorId)
            ?: return ConnectorOutput.Failure("not_found", "Connector '$connectorId' not registered")
        val key = "${connectorId}::${input.action}_${System.currentTimeMillis()}"
        trackStart(key, InflightAction(connectorId, input.action))
        AgentActivityBus.emit("Executing '$connectorId' → ${input.action}", ActivityCategory.CONNECTOR)
        val nonIdempotent = isNonIdempotentAction(connectorId, input.action)
        val retries = if (nonIdempotent) 0 else maxRetries
        return try {
            withTimeout(timeoutMs) { ensureConnected(connector); executeWithRetry(connector, input, retries) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            AgentActivityBus.emit("'$connectorId' timed out after ${timeoutMs}ms", ActivityCategory.CONNECTOR, ActivitySeverity.WARN)
            ConnectorOutput.Failure("timeout", "Timed out after ${timeoutMs}ms", retryable = !nonIdempotent)
        } catch (e: Exception) {
            ConnectorOutput.Failure("runtime_error", e.message ?: "Unknown error")
        } finally { trackEnd(key) }
    }

    suspend fun broadcast(type: ConnectorType, input: ConnectorInput): Map<String, ConnectorOutput> {
        val results = ConcurrentHashMap<String, ConnectorOutput>()
        registry.byType(type).forEach { connector ->
            scope.launch { results[connector.id] = execute(connector.id, input) }
        }
        delay(500)
        return results.toMap()
    }

    private suspend fun ensureConnected(connector: Connector) {
        if (!connector.state().value.connected) connector.connect()
    }

    /** Avoid duplicate external writes when the server acted but the response was lost. */
    private fun isNonIdempotentAction(connectorId: String, action: String): Boolean =
        when (connectorId to action) {
            "telegram" to "send_message",
            "github" to "create_issue",
            "google" to "gmail_send",
            "google" to "calendar_create",
            "ifttt" to "trigger_event",
            "ifttt" to "trigger_applet" -> true
            else -> false
        }

    private suspend fun executeWithRetry(connector: Connector, input: ConnectorInput, maxRetries: Int): ConnectorOutput {
        var last: ConnectorOutput = ConnectorOutput.Failure("not_started", "Never executed")
        for (attempt in 0..maxRetries) {
            last = runCatching { connector.execute(input) }.getOrElse { e ->
                ConnectorOutput.Failure(
                    "exception",
                    e.message ?: "Exception",
                    retryable = !isNonIdempotentAction(connector.id, input.action)
                )
            }
            if (isNonIdempotentAction(connector.id, input.action) && last is ConnectorOutput.Failure && last.retryable) {
                last = last.copy(retryable = false)
            }
            when {
                last is ConnectorOutput.Success   -> { AgentActivityBus.emit(" '${connector.id}' ${input.action}", ActivityCategory.CONNECTOR); return last }
                last is ConnectorOutput.Streaming -> return last
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

    private fun trackStart(key: String, action: InflightAction) { inflight[key] = action; _inflightActions.value = inflight.values.toList() }
    private fun trackEnd(key: String) { inflight.remove(key); _inflightActions.value = inflight.values.toList() }
}
