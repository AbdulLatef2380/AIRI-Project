package com.airi.assistant.connector.app

import android.net.Uri
import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.integrations.google.GoogleAuthService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
 *   - gmail_list      : reads recent message identifiers
 *   - gmail_read      : reads one message
 *   - calendar_list   : reads upcoming events
 *   - drive_search    : searches file metadata
 *
 * Write actions (gmail_send and calendar_create) are rejected in this release:
 * OAuth consent alone is not a durable, reviewable approval for a side effect.
 * Authentication uses Google Identity and a memory-only OAuth access token issued
 * after the user explicitly authorizes the read-only connection in Integrations.
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
        tags        = listOf("google", "gmail", "calendar", "drive", "email"),
        provider = "Google",
        category = "Google",
        authenticationType = ConnectorAuthenticationType.OAUTH2,
        capabilities = listOf(
            ConnectorCapability("email.read", "Read authorized Gmail messages"),
            ConnectorCapability("calendar.read", "Read upcoming Calendar events"),
            ConnectorCapability("drive.read", "Search authorized Drive files"),
            ConnectorCapability("documents.read", "Read an authorized Google document"),
            ConnectorCapability("spreadsheets.read", "Read an authorized spreadsheet range"),
            ConnectorCapability("contacts.read", "Read authorized contacts"),
            ConnectorCapability("tasks.read", "Read authorized Google Tasks"),
        ),
        availability = ConnectorAvailability.PARTIAL,
        website = "https://www.google.com",
        documentationUrl = "https://developers.google.com/identity/protocols/oauth2",
    )
    override fun agentActions() = listOf(
        ConnectorAgentAction("gmail_list", "List authorized Gmail messages.", surfaceId = "google_gmail", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_GMAIL_READONLY)
        ), parameters = mapOf(
            "max_results" to ConnectorAgentParameter(type = "int", description = "Maximum messages to list", minInt = 1, maxInt = 100),
            "query" to ConnectorAgentParameter(description = "Validated Gmail search query", maxLength = 512),
        )),
        ConnectorAgentAction("gmail_read", "Read an authorized Gmail message.", surfaceId = "google_gmail", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_GMAIL_READONLY)
        ), parameters = mapOf(
            "message_id" to ConnectorAgentParameter(description = "Gmail message ID", required = true, maxLength = 512)
        )),
        ConnectorAgentAction("calendar_list", "List upcoming authorized Google Calendar events.", surfaceId = "google_calendar", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_CALENDAR_EVENTS_OWNED_READONLY)
        ), parameters = mapOf(
            "max_results" to ConnectorAgentParameter(type = "int", description = "Maximum upcoming events to list", minInt = 1, maxInt = 100)
        )),
        ConnectorAgentAction("drive_search", "Search authorized Google Drive files.", surfaceId = "google_drive", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_DRIVE_METADATA_READONLY)
        ), parameters = mapOf(
            "query" to ConnectorAgentParameter(description = "Drive search query", required = true, maxLength = 2_048)
        )),
        ConnectorAgentAction("docs_read", "Read an authorized Google document.", surfaceId = "google_docs", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_DOCS_READONLY)
        ), parameters = mapOf("document_id" to ConnectorAgentParameter(description = "Google document id", required = true, maxLength = 256))),
        ConnectorAgentAction("sheets_read", "Read an authorized Google Sheets range.", surfaceId = "google_sheets", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_SHEETS_READONLY)
        ), parameters = mapOf(
            "spreadsheet_id" to ConnectorAgentParameter(description = "Google spreadsheet id", required = true, maxLength = 256),
            "range" to ConnectorAgentParameter(description = "A1 notation range", required = true, maxLength = 512),
        )),
        ConnectorAgentAction("contacts_list", "List authorized Google contacts.", surfaceId = "google_contacts", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_CONTACTS_READONLY)
        ), parameters = mapOf("max_results" to ConnectorAgentParameter(type = "int", description = "Maximum contacts to list", minInt = 1, maxInt = 100))),
        ConnectorAgentAction("tasks_list", "List authorized Google Tasks.", surfaceId = "google_tasks", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.GOOGLE_TASKS_READONLY)
        ), parameters = mapOf("max_results" to ConnectorAgentParameter(type = "int", description = "Maximum tasks to list", minInt = 1, maxInt = 100))),
    )
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val email = googleAuthService.getLastSignedInEmail()
        val accessToken = googleAuthService.getDataAccessToken()
        _state.value = when {
            email.isNullOrBlank() -> ConnectorState(
                connected = false,
                healthy = false,
                statusLine = "Google sign-in required",
                errorMessage = "Sign in before authorizing Google data access."
            )
            accessToken.isNullOrBlank() -> ConnectorState(
                connected = true,
                healthy = false,
                statusLine = "Data authorization required",
                errorMessage = "Authorize the selected Google data surfaces in Integrations."
            )
            else -> ConnectorState(
                connected = true,
                healthy = true,
                statusLine = "Google data access authorized",
                lastUpdatedMs = System.currentTimeMillis()
            )
        }
        _state.value
    }

    override suspend fun disconnect() {
        googleAuthService.disconnect()
        _state.value = ConnectorState(connected = false, healthy = false, statusLine = "Not signed in")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val token = googleAuthService.getDataAccessToken()
        if (token.isNullOrBlank()) {
            _state.value = ConnectorState(
                connected = !googleAuthService.getLastSignedInEmail().isNullOrBlank(),
                healthy = false,
                statusLine = "Data authorization required",
                errorMessage = "Authorize Google data access in Integrations."
            )
            return@withContext ConnectorOutput.Failure(
                code = "authorization_required",
                message = "Google data access requires user authorization in Integrations."
            )
        }
        GoogleConnectorActionPolicy.blockedWriteAction(input.action)?.let { message ->
            return@withContext ConnectorOutput.Failure(
                code = "approval_required",
                message = message
            )
        }
        when (input.action) {
            "gmail_list" -> executeGmailList(token, input)
            "gmail_read" -> executeGmailRead(token, input)
            "calendar_list" -> executeCalendarList(token, input)
            "drive_search" -> executeDriveSearch(token, input)
            "docs_read" -> executeDocsRead(token, input)
            "sheets_read" -> executeSheetsRead(token, input)
            "contacts_list" -> executeContactsList(token, input)
            "tasks_list" -> executeTasksList(token, input)
            else -> ConnectorOutput.Failure(
                code    = "unknown_action",
                message = "Unknown Google action: ${input.action}"
            )
        }
    }

    // ── Gmail (read-only) ─────────────────────────────────────────────────────

    private fun executeGmailList(token: String, input: ConnectorInput): ConnectorOutput {
        val maxResults = input.params["max_results"]?.toIntOrNull() ?: 10
        return try {
            val query = input.params["query"].orEmpty().trim()
            val queryPart = if (query.isBlank()) "" else "&q=${java.net.URLEncoder.encode(query, "UTF-8") }"
            val url = "https://gmail.googleapis.com/gmail/v1/users/me/messages?maxResults=$maxResults$queryPart"
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
            Log.w(TAG, "gmail_list request failed")
            googleRequestFailure("Gmail list", e)
        }
    }

    private fun executeGmailRead(token: String, input: ConnectorInput): ConnectorOutput {
        val messageId = input.params["message_id"].orEmpty()
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
            val body = GmailMessageDecoder.decode(payload)
            ConnectorOutput.Success(
                text = "Subject: $subject\n\n${body.ifBlank { snippet }}",
                data = mapOf("messageId" to messageId, "subject" to subject, "hasBody" to body.isNotBlank().toString())
            )
        } catch (e: Exception) {
            Log.w(TAG, "gmail_read request failed")
            googleRequestFailure("Gmail read", e)
        }
    }

    // ── Calendar (read-only) ──────────────────────────────────────────────────

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
            Log.w(TAG, "calendar_list request failed")
            googleRequestFailure("Calendar list", e)
        }
    }

    // ── Drive ─────────────────────────────────────────────────────────────────

    private fun executeDriveSearch(token: String, input: ConnectorInput): ConnectorOutput {
        val query = input.params["query"] ?: return ConnectorOutput.Failure("invalid_params", "'query' is required")
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
            Log.w(TAG, "drive_search request failed")
            googleRequestFailure("Drive search", e)
        }
    }

    private fun executeDocsRead(token: String, input: ConnectorInput): ConnectorOutput {
        val documentId = input.params["document_id"]?.trim().orEmpty()
        if (documentId.isBlank()) return ConnectorOutput.Failure("invalid_params", "document_id is required")
        return try {
            val response = get("https://docs.googleapis.com/v1/documents/${Uri.encode(documentId)}", token)
            ConnectorOutput.Success(response.toString(2), data = mapOf("documentId" to documentId))
        } catch (e: Exception) {
            Log.w(TAG, "docs_read request failed")
            googleRequestFailure("Google Docs read", e)
        }
    }

    private fun executeSheetsRead(token: String, input: ConnectorInput): ConnectorOutput {
        val spreadsheetId = input.params["spreadsheet_id"]?.trim().orEmpty()
        val range = input.params["range"]?.trim().orEmpty()
        if (spreadsheetId.isBlank() || range.isBlank()) return ConnectorOutput.Failure("invalid_params", "spreadsheet_id and range are required")
        return try {
            val url = "https://sheets.googleapis.com/v4/spreadsheets/${Uri.encode(spreadsheetId)}/values/${Uri.encode(range)}"
            val response = get(url, token)
            ConnectorOutput.Success(response.toString(2), data = mapOf("spreadsheetId" to spreadsheetId, "range" to range))
        } catch (e: Exception) {
            Log.w(TAG, "sheets_read request failed")
            googleRequestFailure("Google Sheets read", e)
        }
    }

    private fun executeContactsList(token: String, input: ConnectorInput): ConnectorOutput {
        val maxResults = input.params["max_results"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
        return try {
            val url = "https://people.googleapis.com/v1/people/me/connections?pageSize=$maxResults&personFields=names,emailAddresses"
            val response = get(url, token)
            ConnectorOutput.Success(response.toString(2), data = mapOf("count" to (response.optJSONArray("connections")?.length() ?: 0).toString()))
        } catch (e: Exception) {
            Log.w(TAG, "contacts_list request failed")
            googleRequestFailure("Google Contacts list", e)
        }
    }

    private fun executeTasksList(token: String, input: ConnectorInput): ConnectorOutput {
        val maxResults = input.params["max_results"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
        return try {
            val url = "https://tasks.googleapis.com/tasks/v1/lists/@default/tasks?maxResults=$maxResults&showCompleted=false&showHidden=false"
            val response = get(url, token)
            ConnectorOutput.Success(response.toString(2), data = mapOf("count" to (response.optJSONArray("items")?.length() ?: 0).toString()))
        } catch (e: Exception) {
            Log.w(TAG, "tasks_list request failed")
            googleRequestFailure("Google Tasks list", e)
        }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun get(url: String, token: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GoogleApiHttpException(response.code)
            }
            JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun googleRequestFailure(operation: String, error: Exception): ConnectorOutput.Failure =
        if (error is GoogleApiHttpException) {
            val authorizationInvalid = error.statusCode == 401 || error.statusCode == 403
            if (authorizationInvalid) googleAuthService.clearDataAccessToken()
            ConnectorOutput.Failure(
                code = if (authorizationInvalid) "authorization_required" else "api_error",
                message = if (authorizationInvalid) {
                    "Google data authorization must be renewed in Integrations."
                } else {
                    "$operation request was rejected (HTTP ${error.statusCode})."
                },
                retryable = error.statusCode >= 500
            )
        } else {
            ConnectorOutput.Failure(
                code = "network_error",
                message = "$operation request could not be completed.",
                retryable = true
            )
        }

    private class GoogleApiHttpException(val statusCode: Int) : Exception()
}
