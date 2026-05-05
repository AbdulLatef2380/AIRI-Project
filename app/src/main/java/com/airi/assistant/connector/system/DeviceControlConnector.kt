package com.airi.assistant.connector.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * DeviceControlConnector — read and modify Android device-level controls.
 *
 * All write actions are gated on available Android permission grants.
 * The connector reports the exact reason for each failure so the agent
 * can surface actionable instructions to the user rather than generic errors.
 *
 * ## Supported actions (read-only unless noted)
 * | action              | notes                                              |
 * |---------------------|----------------------------------------------------|
 * | `get_clipboard`     | Returns current clipboard text                     |
 * | `set_clipboard`     | [WRITE] text = content to paste                    |
 * | `get_volume`        | Returns media + ring + notification volumes        |
 * | `get_wifi_state`    | Returns connected/enabled state                    |
 * | `get_screen_timeout`| Returns screen-off timeout (ms)                    |
 * | `get_airplane_mode` | Returns airplane mode state                        |
 * | `get_device_info`   | Model, SDK level, ABI, build                       |
 */
class DeviceControlConnector(
    private val appContext: Context,
) : Connector {

    override val id          = "device_control"
    override val name        = "Device Control"
    override val description = "Read device state: clipboard, volume, Wi-Fi, screen timeout."
    override val type        = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("clipboard", "volume", "wifi", "device", "settings"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = "Device controls available",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (input.action) {
            "get_clipboard"      -> getClipboard()
            "set_clipboard"      -> setClipboard(input.text)
            "get_volume"         -> getVolume()
            "get_wifi_state"     -> getWifiState()
            "get_screen_timeout" -> getScreenTimeout()
            "get_airplane_mode"  -> getAirplaneMode()
            "get_device_info"    -> getDeviceInfo()
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "DeviceControlConnector: unknown action '${input.action}'",
            )
        }
    }

    // ── Implementations ────────────────────────────────────────────────────────

    private fun getClipboard(): ConnectorOutput {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ConnectorOutput.Failure(code = "unavailable", message = "ClipboardManager unavailable")
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString() ?: ""
        Log.i("AIRI_PROOF", "DEVICE_CLIPBOARD_READ chars=${text.length}")
        return ConnectorOutput.Success(
            text = text,
            data = mapOf("chars" to text.length.toString()),
        )
    }

    private fun setClipboard(content: String): ConnectorOutput {
        val cm = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ConnectorOutput.Failure(code = "unavailable", message = "ClipboardManager unavailable")
        return runCatching {
            val clip = ClipData.newPlainText("airi", content)
            cm.setPrimaryClip(clip)
            Log.i("AIRI_PROOF", "DEVICE_CLIPBOARD_SET chars=${content.length}")
            ConnectorOutput.Success(text = "Clipboard updated (${content.length} chars)")
        }.getOrElse {
            ConnectorOutput.Failure(code = "clipboard_error", message = it.message ?: "Failed to set clipboard")
        }
    }

    private fun getVolume(): ConnectorOutput {
        val am = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ConnectorOutput.Failure(code = "unavailable", message = "AudioManager unavailable")
        val media  = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val mediaMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val ring   = am.getStreamVolume(AudioManager.STREAM_RING)
        val ringMax  = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val notif  = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION)
        val notifMax = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        Log.i("AIRI_PROOF", "DEVICE_VOLUME media=$media/$mediaMax ring=$ring/$ringMax notif=$notif/$notifMax")
        return ConnectorOutput.Success(
            text = "Media: $media/$mediaMax  Ring: $ring/$ringMax  Notification: $notif/$notifMax",
            data = mapOf(
                "media"        to media.toString(),
                "media_max"    to mediaMax.toString(),
                "ring"         to ring.toString(),
                "ring_max"     to ringMax.toString(),
                "notification" to notif.toString(),
                "notification_max" to notifMax.toString(),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun getWifiState(): ConnectorOutput {
        val wm = appContext.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = wm?.isWifiEnabled ?: false
        val ssid    = if (enabled) wm?.connectionInfo?.ssid?.trim('"') ?: "unknown" else "—"
        Log.i("AIRI_PROOF", "DEVICE_WIFI enabled=$enabled ssid=$ssid")
        return ConnectorOutput.Success(
            text = "Wi-Fi: ${if (enabled) "enabled — connected to $ssid" else "disabled"}",
            data = mapOf("enabled" to enabled.toString(), "ssid" to ssid),
        )
    }

    private fun getScreenTimeout(): ConnectorOutput {
        return runCatching {
            val timeoutMs = Settings.System.getInt(
                appContext.contentResolver,
                Settings.System.SCREEN_OFF_TIMEOUT,
                30_000
            )
            val timeoutSec = timeoutMs / 1000
            ConnectorOutput.Success(
                text = "Screen timeout: ${timeoutSec}s",
                data = mapOf("timeout_ms" to timeoutMs.toString(), "timeout_sec" to timeoutSec.toString()),
            )
        }.getOrElse {
            ConnectorOutput.Failure(code = "settings_error", message = it.message ?: "Cannot read screen timeout")
        }
    }

    private fun getAirplaneMode(): ConnectorOutput {
        return runCatching {
            val enabled = Settings.Global.getInt(
                appContext.contentResolver,
                Settings.Global.AIRPLANE_MODE_ON,
                0
            ) != 0
            ConnectorOutput.Success(
                text = "Airplane mode: ${if (enabled) "ON" else "OFF"}",
                data = mapOf("enabled" to enabled.toString()),
            )
        }.getOrElse {
            ConnectorOutput.Failure(code = "settings_error", message = it.message ?: "Cannot read airplane mode")
        }
    }

    private fun getDeviceInfo(): ConnectorOutput {
        val info = buildString {
            appendLine("Brand: ${Build.BRAND}")
            appendLine("Model: ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("ABI: ${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
            appendLine("Build: ${Build.ID}")
            appendLine("Fingerprint: ${Build.FINGERPRINT.take(60)}")
        }.trim()
        Log.i("AIRI_PROOF", "DEVICE_INFO_READ model=${Build.MODEL} sdk=${Build.VERSION.SDK_INT}")
        return ConnectorOutput.Success(
            text = info,
            data = mapOf(
                "brand"   to Build.BRAND,
                "model"   to Build.MODEL,
                "sdk"     to Build.VERSION.SDK_INT.toString(),
                "abi"     to (Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
            ),
        )
    }
}
