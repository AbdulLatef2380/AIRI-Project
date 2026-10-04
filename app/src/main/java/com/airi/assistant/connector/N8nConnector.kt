package com.airi.assistant.connector

import android.util.Log
import com.airi.assistant.domain.release.ReleaseScopePolicy
import com.airi.assistant.tools.N8nIntegration
import com.airi.assistant.tools.N8nWebhookUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** N8n webhook connector. A configured URL is not healthy until /healthz responds. */
class N8nConnector(
    private val authManager: ConnectorAuthManager
) : Connector {
    private val tag = "N8nConnector"
    override val id = "n8n"
    override val name = "N8n"
    override val description = "Trigger N8n workflow automation via a validated webhook URL."
    override val type = ConnectorType.API

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, healthy = false, statusLine = "No webhook URL configured")
    )
    private val lifecycleMutex = Mutex()
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override fun agentActions() = listOf(
        ConnectorAgentAction(
            id = "trigger_workflow",
            description = "Send a bounded request to the configured N8n webhook after explicit approval.",
            surfaceId = id,
            permission = ConnectorPermissionLevel.WRITE,
            requiresConfirmation = true,
            parameters = mapOf(
                "intent" to ConnectorAgentParameter(required = true, maxLength = 100),
                "title" to ConnectorAgentParameter(maxLength = 120),
                "priority" to ConnectorAgentParameter(maxLength = 32),
                "context" to ConnectorAgentParameter(maxLength = 2_000),
                "language" to ConnectorAgentParameter(maxLength = 35),
            ),
            providerGrants = listOf(ConnectorProviderGrant(
                ConnectorProviderGrantKind.WEBHOOK_ENDPOINT_AUTHORITY,
                "User-configured HTTPS N8n webhook URL accepted by N8nWebhookUrlPolicy",
            )),
        )
    )

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    override fun meta() = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        iconUrl = null,
        tags = listOf("n8n", "automation", "webhook", "workflow")
    )

    private fun webhookUrl(): String? = authManager.getCredential(id, "webhook_url")

    /** Explicit configuration is the only operation that re-enables a disconnected endpoint. */
    suspend fun configureWebhookUrl(rawUrl: String): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (N8nWebhookUrlPolicy.validate(rawUrl) !is N8nWebhookUrlPolicy.Validation.Accepted) {
                return@withLock false
            }
            if (!authManager.storeCredential(id, "webhook_url", rawUrl.trim())) return@withLock false
            if (!authManager.setExplicitlyDisconnected(id, false)) {
                authManager.setExplicitlyDisconnected(id, true)
                authManager.clearCredential(id, "webhook_url")
                return@withLock false
            }
            _state.value = ConnectorState(false, false, "Webhook configured; health not yet checked")
            true
        }
    }

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            if (!ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
                _state.value = ConnectorState(
                    connected = false,
                    healthy = false,
                    statusLine = "Unavailable in this release",
                    errorMessage = "External automation integrations are unavailable."
                )
                return@withLock _state.value
            }
            if (authManager.isExplicitlyDisconnected(id)) {
                _state.value = ConnectorState(false, false, "Disconnected")
                return@withLock _state.value
            }
            val rawUrl = webhookUrl()
            if (rawUrl.isNullOrBlank()) {
                _state.value = ConnectorState(
                    false,
                    false,
                    "No webhook URL configured",
                    errorMessage = "Configure the N8n webhook URL first."
                )
                return@withLock _state.value
            }
            val accepted = N8nWebhookUrlPolicy.validate(rawUrl)
            if (accepted !is N8nWebhookUrlPolicy.Validation.Accepted) {
                _state.value = ConnectorState(
                    false,
                    false,
                    "Invalid webhook configuration",
                    errorMessage = "Use HTTPS, or HTTP only for a loopback development endpoint."
                )
                return@withLock _state.value
            }
            val healthy = try {
                val request = Request.Builder().url(accepted.healthCheck.toURL()).get().build()
                healthClient.newCall(request).execute().use { it.isSuccessful }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(tag, "N8N_HEALTH_CHECK_FAILED type=${error.javaClass.simpleName}")
                false
            }
            _state.value = ConnectorState(
                connected = true,
                healthy = healthy,
                statusLine = if (healthy) "Connected and healthy" else "Webhook configured; health could not be confirmed",
                lastUpdatedMs = System.currentTimeMillis(),
                errorMessage = if (healthy) null else "N8n health check did not return a successful response."
            )
            _state.value
        }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            val disabled = authManager.setExplicitlyDisconnected(id, true)
            val cleared = authManager.clearCredential(id, "webhook_url")
            _state.value = ConnectorState(
                connected = false,
                healthy = false,
                statusLine = if (disabled && cleared) "Disconnected" else "Disconnected; cleanup needs attention",
                errorMessage = if (disabled && cleared) null else "Could not confirm durable disconnect cleanup."
            )
        }
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (input.action != "trigger_workflow") {
            return@withContext ConnectorOutput.Failure(
                "unknown_action", "N8nConnector accepts only the declared 'trigger_workflow' action."
            )
        }
        lifecycleMutex.withLock {
            if (!ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
                return@withLock ConnectorOutput.Failure(
                    "integration_unavailable",
                    "External automation integrations are unavailable in this release."
                )
            }
            if (!_state.value.connected || !_state.value.healthy || authManager.isExplicitlyDisconnected(id)) {
                return@withLock ConnectorOutput.Failure(
                    "not_connected",
                    "N8n is not confirmed healthy and connected.",
                    retryable = false
                )
            }
            val rawUrl = webhookUrl()
                ?: return@withLock ConnectorOutput.Failure(
                    "auth_required",
                    "Configure the N8n webhook URL in Connectors settings first."
                )
            val accepted = N8nWebhookUrlPolicy.validate(rawUrl)
            if (accepted !is N8nWebhookUrlPolicy.Validation.Accepted) {
                return@withLock ConnectorOutput.Failure(
                    "invalid_endpoint",
                    "The N8n webhook endpoint is not allowed.",
                    retryable = false
                )
            }
            val intent = input.params["intent"]
                ?: return@withLock ConnectorOutput.Failure("invalid_params", "The declared N8n intent parameter is required.")
            val result = try {
                N8nIntegration(accepted.webhook.toString()).sendAutomationRequest(
                    intent = intent,
                    action = "trigger_workflow",
                    title = input.params["title"] ?: intent.take(60),
                    priority = input.params["priority"] ?: "medium",
                    context = input.params["context"] ?: "general",
                    userId = "user_001",
                    language = input.params["language"] ?: "en",
                    sessionId = "airi-${System.currentTimeMillis()}"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(tag, "N8N_EXECUTION_FAILED type=${error.javaClass.simpleName}")
                null
            }
            if (result == null) {
                // A POST may have reached n8n even when the response was lost;
                // retrying here can duplicate the automation side effect.
                ConnectorOutput.Failure("network_error", "N8n did not confirm the workflow request; outcome is unknown.", retryable = false)
            } else {
                ConnectorOutput.Success(
                    text = result.ifBlank { "N8n workflow triggered successfully." },
                    data = mapOf("action" to input.action)
                )
            }
        }
    }
}
