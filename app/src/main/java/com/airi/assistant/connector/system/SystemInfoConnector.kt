package com.airi.assistant.connector.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SYSTEM-bucket connector that surfaces device telemetry: battery level,
 * charging state, network connectivity. Read-only; never mutates device
 * state, so it's always-on and never needs auth.
 *
 * Intentionally narrow scope: more sensors get their own connectors
 * rather than bloating this one (keeps unknown_action diagnostics tight).
 */
class SystemInfoConnector(
    private val appContext: Context,
) : Connector {

    override val id = "system_info"
    override val name = "Device System"
    override val description = "Battery, network, and basic device telemetry."
    override val type = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("battery", "network", "device"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = "Telemetry available",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        return when (input.action) {
            "battery_status"  -> batteryStatus()
            "network_status"  -> networkStatus()
            "system_exec"     -> systemSnapshot()
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "SystemInfoConnector does not handle '${input.action}'",
            )
        }
    }

    private fun batteryStatus(): ConnectorOutput {
        val intent: Intent = appContext.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ConnectorOutput.Failure(
            code = "unavailable", message = "Battery info unavailable",
        )
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct   = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val statusInt = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging =
            statusInt == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusInt == BatteryManager.BATTERY_STATUS_FULL
        return ConnectorOutput.Success(
            text = "Battery: $pct% ${if (charging) "(charging)" else ""}".trim(),
            data = mapOf(
                "level_percent" to pct.toString(),
                "charging" to charging.toString(),
            ),
        )
    }

    private fun networkStatus(): ConnectorOutput {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager
            ?: return ConnectorOutput.Failure(
                code = "unavailable", message = "ConnectivityManager unavailable",
            )
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                     caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val transport = when {
            caps == null                                                       -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)              -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)          -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)          -> "ethernet"
            else                                                               -> "other"
        }
        return ConnectorOutput.Success(
            text = if (online) "Online ($transport)" else "Offline",
            data = mapOf("online" to online.toString(), "transport" to transport),
        )
    }

    private fun systemSnapshot(): ConnectorOutput {
        val battery = batteryStatus()
        val network = networkStatus()
        val parts = listOfNotNull(
            (battery as? ConnectorOutput.Success)?.text,
            (network as? ConnectorOutput.Success)?.text,
        )
        return ConnectorOutput.Success(text = parts.joinToString(" • "))
    }
}
