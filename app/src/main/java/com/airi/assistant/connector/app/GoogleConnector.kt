package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.integrations.google.GoogleAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AP-10: GoogleConnector — first-class Google integration connector.
 *
 * Registered as connector #14 in [ConnectorBootstrap]. Resolves runtime crashes
 * in GmailAssistantSkill, CalendarEventsSkill, and DriveSearchSkill — all three
 * called ToolExecutor.route("google", ...) which previously returned null because
 * no Google connector was registered.
 *
 * Supported actions:
 *   - gmail_list      : GET /gmail/v1/users/me/messages (recent messages)
 *   - gmail_read      : GET /gmail/v1/users/me/messages/{id} (single message)
 *   - gmail_send      : POST /gmail/v1/users/me/messages/send
 *   - calendar_list   : GET /calendar/v3/calendars/primary/events
 *   - calendar_create : POST /calendar/v3/calendars/primary/events
 *   - drive_search    : GET /drive/v3/files?q=...
 *
 * Authentication: Google Sign-In (GoogleAuthService) using the user's Google account.
 * The connector requires the user to be signed in via the Integrations screen.
 * Unauthenticated requests return ConnectorOutput.Failure(code = "auth_required").
 */
class GoogleConnector(private val googleAuthService: GoogleAuthService) : Connector {

    private val TAG = "GoogleConnector"

    override val id          = "google"
    override val name        = "Google"
    override val description = "Gmail, Calendar, and Drive access via Google account."
    override val type        = ConnectorType.APP

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, statusLine = "Not signed in")
    )
    override fun meta() = ConnectorMeta(
        id          = id,
        name        = name,
        description = description,
        type        = type,
        iconUrl     = null,
        tags        = listOf("google", "gmail", "calendar", "drive", "email")
    )
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val email = googleAuthService.getLastSignedInEmail()
        if (email != null) {
            _state.value = ConnectorState(
                connected  = true,
                statusLine = "Signed in as $email"
            )
        } else {
            _state.value = ConnectorState(
                connected    = false,
                statusLine   = "Not signed in",
                errorMessage = "Sign in with Google in the Integrations screen"
            )
        }
        _state.value
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false, statusLine = "Not signed in")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val token = googleAuthService.getIdToken()
        if (token.isNullOrBlank()) {
            return@withContext ConnectorOutput.Failure(
                code    = "auth_required",
                message = "Sign in with Google in the Integrations screen to use this feature."
            )
        }
        when (input.action) {
            "gmail_list"      -> executeGmailList(token, input)
            "gmail_read"      -> executeGmailRead(token, input)
            "gmail_send"      -> executeGmailSend(token, input)
            "calendar_list"   -> executeCalendarList(token, input)
            "calendar_create" -> executeCalendarCreate(token, input)
            "drive_search"    -> executeDriveSearch(token, input)
            else -> ConnectorOutput.Failure(
                code    = "unknown_action",
                message = "Unknown Google action: ${input.action}"
            )
        }
    }

    // ── Gmail ─────────────────────────────────────────────────────────────────

    private fun executeGmailList(token: String, input: ConnectorInput): ConnectorOutput {
        val maxResults = input.params["max_results"]?.toIntOrNull() ?: 10
        return try {
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=$maxResults"
            val response = get(url, token)
            val messages = response.optJSONArray("messages")
            val count = messages?.length() ?: 0
            val preview = buildString {
                for (i in 0 until minOf(count, maxResults)) {
                    val msg = messages?.optJSONObject(i)
                    if (msg != null) appendLine("Message ID: ${msg.optString("id")}")
                }
            }
            ConnectorOutput.Success(
                text = if (count > 0) "Found $count messages:\n$preview" else "No messages found.",
                data = mapOf("count" to count.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "gmail_list error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Gmail list failed: ${e.message}", retryable = true)
        }
    }

    private fun executeGmailRead(token: String, input: ConnectorInput): ConnectorOutput {
        val messageId = input.params["message_id"] ?: input.text.trim()
        if (messageId.isBlank()) return ConnectorOutput.Failure("invalid_params", "message_id is required")
        return try {
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages/$messageId?format=full"
            val response = get(url, token)
            val payload = response.optJSONObject("payload")
            val headers = payload?.optJSONArray("headers")
            val subject = (0 until (headers?.length() ?: 0))
                .map { headers!!.optJSONObject(it) }
                .firstOrNull { it?.optString("name") == "Subject" }
                ?.optString("value") ?: "(no subject)"
            val snippet = response.optString("snippet", "")
            ConnectorOutput.Success(
                text = "Subject: $subject\n\n$snippet",
                data = mapOf("messageId" to messageId, "subject" to subject)
            )
        } catch (e: Exception) {
            Log.e(TAG, "gmail_read error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Gmail read failed: ${e.message}", retryable = true)
        }
    }

    private fun executeGmailSend(token: String, input: ConnectorInput): ConnectorOutput {
        val to      = input.params["to"]      ?: return ConnectorOutput.Failure("invalid_params", "'to' is required")
        val subject = input.params["subject"] ?: "(no subject)"
        val body    = input.params["body"]    ?: input.text
        return try {
            val raw = buildString {
                append("To: $to\r\n")
                append("Subject: $subject\r\n")
                append("Content-Type: text/plain; charset=utf-8\r\n\r\n")
                append(body)
            }
            val encoded = android.util.Base64.encodeToString(raw.toByteArray(), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
            val requestBody = JSONObject().put("raw", encoded).toString()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
            val response = http.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                ConnectorOutput.Success(text = "Email sent to $to", data = mapOf("to" to to, "subject" to subject))
            } else {
                ConnectorOutput.Failure(code = "api_error", message = "Gmail send failed (${response.code}): $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "gmail_send error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Gmail send failed: ${e.message}", retryable = true)
        }
    }

    // ── Calendar ──────────────────────────────────────────────────────────────

    private fun executeCalendarList(token: String, input: ConnectorInput): ConnectorOutput {
        val maxResults = input.params["max_results"]?.toIntOrNull() ?: 10
        return try {
            val now = java.time.Instant.now().toString()
            val url = "https://www.googleapis.com/calendar/v3/calendars/primary/events" +
                "?maxResults=$maxResults&orderBy=startTime&singleEvents=true&timeMin=$now"
            val response = get(url, token)
            val items = response.optJSONArray("items")
            val count = items?.length() ?: 0
            val summary = buildString {
                for (i in 0 until count) {
                    val event = items?.optJSONObject(i) ?: continue
                    val title = event.optString("summary", "(no title)")
                    val start = event.optJSONObject("start")?.optString("dateTime")
                        ?: event.optJSONObject("start")?.optString("date") ?: "?"
                    appendLine("• $title — $start")
                }
            }
            ConnectorOutput.Success(
                text = if (count > 0) "Upcoming events:\n$summary" else "No upcoming events.",
                data = mapOf("count" to count.toString())
            )
        } catch (e: Exception) {
            Log.e(TAG, "calendar_list error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Calendar list failed: ${e.message}", retryable = true)
        }
    }

    private fun executeCalendarCreate(token: String, input: ConnectorInput): ConnectorOutput {
        val title    = input.params["title"]    ?: input.text.trim().ifBlank { return ConnectorOutput.Failure("invalid_params", "'title' is required") }
        val start    = input.params["start"]    ?: return ConnectorOutput.Failure("invalid_params", "'start' datetime is required (ISO 8601)")
        val end      = input.params["end"]      ?: start
        val timeZone = input.params["timezone"] ?: "UTC"
        return try {
            val event = JSONObject().apply {
                put("summary", title)
                put("start", JSONObject().apply {
                    put("dateTime", start); put("timeZone", timeZone)
                })
                put("end", JSONObject().apply {
                    put("dateTime", end); put("timeZone", timeZone)
                })
            }
            val requestBody = event.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://www.googleapis.com/calendar/v3/calendars/primary/events")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()
            val response = http.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                val created = JSONObject(responseBody)
                ConnectorOutput.Success(
                    text = "Event created: $title",
                    data = mapOf("eventId" to created.optString("id"), "title" to title)
                )
            } else {
                ConnectorOutput.Failure(code = "api_error", message = "Calendar create failed (${response.code}): $responseBody")
            }
        } catch (e: Exception) {
            Log.e(TAG, "calendar_create error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Calendar create failed: ${e.message}", retryable = true)
        }
    }

    // ── Drive ─────────────────────────────────────────────────────────────────

    private fun executeDriveSearch(token: String, input: ConnectorInput): ConnectorOutput {
        val query = input.params["query"] ?: input.text.trim().ifBlank { return ConnectorOutput.Failure("invalid_params", "'query' is required") }
        val maxResults = input.params["max_results"]?.toIntOrNull() ?: 10
        return try {
            val encodedQ = java.net.URLEncoder.encode("name contains '$query'", "UTF-8")
            val url = "https://www.googleapis.com/drive/v3/files?q=$encodedQ&pageSize=$maxResults&fields=files(id,name,mimeType,modifiedTime)"
            val response = get(url, token)
            val files = response.optJSONArray("files")
            val count = files?.length() ?: 0
            val summary = buildString {
                for (i in 0 until count) {
                    val file = files?.optJSONObject(i) ?: continue
                    val name = file.optString("name", "?")
                    val type = file.optString("mimeType", "?").substringAfterLast(".")
                    appendLine("• $name ($type)")
                }
            }
            ConnectorOutput.Success(
                text = if (count > 0) "Found $count files matching \"$query\":\n$summary" else "No files found for \"$query\".",
                data = mapOf("count" to count.toString(), "query" to query)
            )
        } catch (e: Exception) {
            Log.e(TAG, "drive_search error: ${e.message}")
            ConnectorOutput.Failure(code = "network_error", message = "Drive search failed: ${e.message}", retryable = true)
        }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun get(url: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        val response = http.newCall(request).execute()
        val body = response.body?.string() ?: "{}"
        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $body")
        }
        return JSONObject(body)
    }
}
