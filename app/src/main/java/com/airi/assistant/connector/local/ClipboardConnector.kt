package com.airi.assistant.connector.local

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
 * ClipboardConnector — read/write Android clipboard for agent use.
 *
 * Actions:
 *  - read   → returns current clipboard text in ConnectorOutput.Success.text
 *  - write  → copies params["text"] to clipboard
 *  - clear  → clears clipboard
 *
 * Always-connected; no auth required.
 * Integration: registered in [ConnectorBootstrap.installDefaults].
 */
class ClipboardConnector(private val appContext: Context) : Connector {

    override val id          = "clipboard"
    override val name        = "Clipboard"
    override val description = "Read and write the Android system clipboard."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("clipboard", "copy", "paste", "text")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(connected = true, healthy = true,
            statusLine = "Clipboard ready", lastUpdatedMs = System.currentTimeMillis())
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ConnectorOutput.Failure("clipboard_unavailable",
                "ClipboardManager not available on this device")

        return when (input.action) {
            "read" -> {
                val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                ConnectorOutput.Success(
                    text = text,
                    data = mapOf("length" to text.length.toString(), "empty" to (text.isEmpty()).toString())
                )
            }
            "write" -> {
                val text = input.params["text"]
                    ?: return ConnectorOutput.Failure("missing_param", "Required param 'text' not provided")
                val label = input.params["label"] ?: "AIRI"
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                ConnectorOutput.Success(
                    text = "Copied to clipboard",
                    data = mapOf("copied" to "true", "length" to text.length.toString())
                )
            }
            "clear" -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
                ConnectorOutput.Success(text = "Clipboard cleared", data = mapOf("cleared" to "true"))
            }
            else -> ConnectorOutput.Failure("unknown_action",
                "Unknown action '${input.action}'. Valid: read, write, clear")
        }
    }
}
