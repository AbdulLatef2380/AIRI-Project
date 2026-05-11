package com.airi.assistant.connector.local

import android.content.Context
import android.net.Uri
import android.provider.Telephony
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SmsConnector — read SMS conversations and send messages via Android SMSManager.
 *
 * ## Supported actions
 * | action              | params                       | notes                          |
 * |---------------------|------------------------------|--------------------------------|
 * | `list_conversations`| `limit` (default 20)         | Recent SMS threads             |
 * | `get_thread`        | `thread_id`, `limit`         | Messages in a thread           |
 * | `search`            | `query`                      | Search message bodies          |
 * | `send`              | `to`, `body`                 | Send SMS (requires SEND_SMS)   |
 *
 * Requires: `READ_SMS` (and `SEND_SMS` for the send action).
 * Returns Failure with code `permission_denied` when not granted.
 *
 * **Privacy note**: SMS content is never logged or transmitted — only
 * the agent's response (which may include content) is treated as sensitive.
 */
class SmsConnector(private val context: Context) : Connector {

    override val id          = "sms"
    override val name        = "SMS"
    override val description = "Read SMS conversations and send text messages"
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(ConnectorState(connected = false))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("sms", "messages", "text", "messaging"))

    override suspend fun connect(): ConnectorState {
        val granted = context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val s = ConnectorState(
            connected  = granted,
            statusLine = if (granted) "SMS access granted" else "READ_SMS permission required"
        )
        _state.value = s
        return s
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false)
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            when (input.action) {
                "list_conversations" -> listConversations(input.params["limit"]?.toIntOrNull() ?: 20)
                "get_thread"         -> getThread(input.params)
                "search"             -> search(input.params["query"] ?: input.text)
                "send"               -> send(input.params)
                else -> ConnectorOutput.Failure("unknown_action",
                    "SmsConnector does not support: ${input.action}")
            }
        } catch (e: SecurityException) {
            ConnectorOutput.Failure("permission_denied", "SMS permission not granted", retryable = false)
        } catch (e: Exception) {
            Log.e(TAG, "SmsConnector error: ${e.message}")
            ConnectorOutput.Failure("sms_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    // ── Implementation ────────────────────────────────────────────────────────

    private fun listConversations(limit: Int): ConnectorOutput {
        val uri  = Telephony.Sms.Conversations.CONTENT_URI
        val proj = arrayOf(
            Telephony.Sms.Conversations.THREAD_ID,
            Telephony.Sms.Conversations.SNIPPET,
            Telephony.Sms.Conversations.MSG_COUNT,
            "recipient_ids"
        )
        val cursor = context.contentResolver.query(uri, proj, null, null,
            "${Telephony.Sms.Conversations.DEFAULT_SORT_ORDER} LIMIT $limit")
            ?: return ConnectorOutput.Failure("query_failed", "SMS conversations query returned null")

        val fmt     = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault())
        val threads = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                threads.put(JSONObject()
                    .put("thread_id",  it.getLong(0))
                    .put("snippet",    (it.getString(1) ?: "").take(100))
                    .put("msg_count",  it.getInt(2)))
            }
        }
        return ConnectorOutput.Success(
            text = "${threads.length()} SMS conversations",
            data = mapOf("threads_json" to threads.toString(), "count" to threads.length().toString())
        )
    }

    private fun getThread(params: Map<String, String>): ConnectorOutput {
        val threadId = params["thread_id"]
            ?: return ConnectorOutput.Failure("missing_param", "thread_id required")
        val limit    = params["limit"]?.toIntOrNull() ?: 30

        val uri    = Telephony.Sms.CONTENT_URI
        val proj   = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE
        )
        val sel    = "${Telephony.Sms.THREAD_ID} = ?"
        val args   = arrayOf(threadId)
        val sort   = "${Telephony.Sms.DATE} DESC LIMIT $limit"
        val cursor = context.contentResolver.query(uri, proj, sel, args, sort)
            ?: return ConnectorOutput.Failure("query_failed", "Thread $threadId not found")

        val fmt  = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val msgs = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                msgs.put(JSONObject()
                    .put("id",      it.getLong(0))
                    .put("address", it.getString(1) ?: "")
                    .put("body",    (it.getString(2) ?: "").take(500))
                    .put("date",    fmt.format(Date(it.getLong(3))))
                    .put("type",    if (it.getInt(4) == Telephony.Sms.MESSAGE_TYPE_SENT) "sent" else "received"))
            }
        }
        return ConnectorOutput.Success(
            text = "${msgs.length()} messages in thread $threadId",
            data = mapOf("messages_json" to msgs.toString())
        )
    }

    private fun search(query: String): ConnectorOutput {
        if (query.isBlank()) return ConnectorOutput.Failure("missing_param", "query must not be empty")

        val uri  = Telephony.Sms.CONTENT_URI
        val proj = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID
        )
        val sel    = "${Telephony.Sms.BODY} LIKE ?"
        val args   = arrayOf("%$query%")
        val sort   = "${Telephony.Sms.DATE} DESC LIMIT 20"
        val cursor = context.contentResolver.query(uri, proj, sel, args, sort)
            ?: return ConnectorOutput.Failure("query_failed", "SMS search failed")

        val fmt     = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val results = JSONArray()
        cursor.use {
            while (it.moveToNext()) {
                results.put(JSONObject()
                    .put("id",       it.getLong(0))
                    .put("address",  it.getString(1) ?: "")
                    .put("body",     (it.getString(2) ?: "").take(200))
                    .put("date",     fmt.format(Date(it.getLong(3))))
                    .put("thread_id", it.getLong(4)))
            }
        }
        return ConnectorOutput.Success(
            text = "${results.length()} messages found matching '$query'",
            data = mapOf("results_json" to results.toString())
        )
    }

    private fun send(params: Map<String, String>): ConnectorOutput {
        val to   = params["to"]   ?: return ConnectorOutput.Failure("missing_param", "to required")
        val body = params["body"] ?: return ConnectorOutput.Failure("missing_param", "body required")

        val sendGranted = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!sendGranted) return ConnectorOutput.Failure("permission_denied", "SEND_SMS required")

        return try {
            val sm = android.telephony.SmsManager.getDefault()
            if (body.length <= 160) {
                sm.sendTextMessage(to, null, body, null, null)
            } else {
                val parts = sm.divideMessage(body)
                sm.sendMultipartTextMessage(to, null, parts, null, null)
            }
            Log.i(TAG, "SMS sent to=$to length=${body.length}")
            ConnectorOutput.Success("SMS sent to $to", data = mapOf("recipient" to to))
        } catch (e: Exception) {
            ConnectorOutput.Failure("send_failed", e.message ?: "Failed to send SMS")
        }
    }

    companion object { private const val TAG = "AIRI_SmsConnector" }
}
