package com.airi.assistant.connector.local

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorAgentAction
import com.airi.assistant.connector.ConnectorAgentParameter
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorPermissionLevel
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * On-device connector that dispatches Android Intents.
 *
 * Handles the LOCAL bucket actions for app launch, URLs, dialer, and
 * Settings shortcuts. Uses the application context only — never holds
 * an Activity reference, so it's safe as a singleton.
 */
class AndroidIntentConnector(
    private val appContext: Context,
) : Connector {

    override val id = "android_intent"
    override val name = "Android System"
    override val description = "Open apps, links, and system screens via Android Intents."
    override val type = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("intent", "system", "open"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override fun agentActions() = listOf(
        ConnectorAgentAction("open_app", "Open an installed application after confirmation.", permission = ConnectorPermissionLevel.WRITE, parameters = mapOf(
            "package" to ConnectorAgentParameter(required = true, maxLength = 255)
        )),
        ConnectorAgentAction("open_url", "Open an allowed URL in an external application after confirmation.", permission = ConnectorPermissionLevel.WRITE, parameters = mapOf(
            "url" to ConnectorAgentParameter(required = true, maxLength = 2_048)
        )),
        ConnectorAgentAction("open_settings", "Open Android system settings after confirmation.", permission = ConnectorPermissionLevel.WRITE),
    )

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = "Ready", lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        when (val decision = DeviceActionPolicy.evaluate(input.action, input.params["url"] ?: input.text)) {
            DeviceActionPolicy.Decision.Allowed -> Unit
            is DeviceActionPolicy.Decision.RequiresUserTakeover -> {
                return ConnectorOutput.Failure(
                    code = "user_takeover_required",
                    message = decision.reason,
                    retryable = false
                )
            }
            is DeviceActionPolicy.Decision.Blocked -> {
                return ConnectorOutput.Failure(
                    code = "blocked_by_policy",
                    message = decision.reason,
                    retryable = false
                )
            }
        }
        return when (input.action) {
            "open_app" -> openApp(input.params["package"].orEmpty())
            "open_url" -> openUrl(input.params["url"].orEmpty())
            "open_settings" -> openSettings()
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "AndroidIntentConnector does not handle '${input.action}'",
            )
        }
    }

    private fun openApp(pkg: String): ConnectorOutput {
        if (pkg.isBlank()) {
            return ConnectorOutput.Failure(
                code = "bad_input", message = "Missing 'package' param",
            )
        }
        val launch = appContext.packageManager.getLaunchIntentForPackage(pkg)
            ?: return ConnectorOutput.Failure(
                code = "not_installed",
                message = "Package not installed: $pkg",
            )
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(launch)
            ConnectorOutput.Success(text = "Launched $pkg")
        }.getOrElse {
            ConnectorOutput.Failure(
                code = "launch_failed",
                message = it.message ?: "startActivity threw",
                retryable = false,
            )
        }
    }

    private fun openUrl(url: String): ConnectorOutput {
        if (url.isBlank()) {
            return ConnectorOutput.Failure(code = "bad_input", message = "Empty URL")
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            ConnectorOutput.Success(text = "Opened $url")
        }.getOrElse {
            ConnectorOutput.Failure(
                code = "no_handler",
                message = "No app can handle $url",
                retryable = false,
            )
        }
    }

    private fun openSettings(): ConnectorOutput {
        val intent = Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
        return ConnectorOutput.Success(text = "Opened Settings")
    }
}
