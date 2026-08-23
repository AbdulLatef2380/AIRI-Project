package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.agent.durable.ApprovalContinuation
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.durable.ResumableConnectorInvocation
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import com.airi.assistant.vault.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubConnector(
    private val authManager: ConnectorAuthManager? = null,
    private val durableTaskManager: DurableTaskManager? = null,
    private val secretVault: SecretVault? = null,
    /** Test seam; production obtains global credentials only from ConnectorAuthManager. */
    private val legacyCredentialProvider: (String, String) -> String? = { connectorId, credentialKey ->
        authManager?.getCredential(connectorId, credentialKey)
    },
    /** Test seam; production validates the exact persisted task/run/step ownership. */
    private val projectExecutionOwnership: (ConnectorExecutionContext) -> Boolean = { execution ->
        durableTaskManager?.ownsConnectorExecution(
            taskId = execution.taskId,
            missionId = execution.missionId,
            projectId = execution.projectId.orEmpty(),
            runId = execution.runId,
            stepId = execution.stepId
        ) == true
    },
    /** Test seam; production keeps the raw credential within the HTTP adapter. */
    private val tokenExecutor: ((ConnectorInput, String) -> ConnectorOutput)? = null
) : Connector {
    private val TAG = "GitHubConnector"
    private val BASE = "https://api.github.com"
    override val id          = "github"
    override val name        = "GitHub"
    override val description = "Access repos, issues, PRs, and code search."
    override val type        = ConnectorType.APP
    private val _state = MutableStateFlow(ConnectorState(connected = false, statusLine = "Not connected"))
    override fun meta() = ConnectorMeta(id, name, description, type, "https://github.com/favicon.ico", listOf("git","code","repo","issues"))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val token = legacyCredentialProvider(id, "pat")
        if (token.isNullOrBlank()) { _state.value = ConnectorState(false, statusLine = "No PAT", errorMessage = "Set a GitHub PAT in Connectors settings"); return@withContext _state.value }
        try {
            val user = apiGet("/user", token)
            val login = user.optString("login", "unknown")
            _state.value = ConnectorState(true, true, "Connected as @$login", System.currentTimeMillis())
            AgentActivityBus.emit("GitHub connected as @$login", ActivityCategory.CONNECTOR)
        } catch (e: Exception) { _state.value = ConnectorState(false, statusLine = "Failed: ${e.message}", errorMessage = e.message) }
        _state.value
    }

    override suspend fun disconnect() { _state.value = ConnectorState(false, statusLine = "Disconnected") }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (val mutation = GitHubMutationPolicy.evaluate(input.action)) {
            GitHubMutationPolicy.Decision.Allowed -> Unit
            is GitHubMutationPolicy.Decision.RequiresTaskApproval -> {
                val execution = input.execution
                val manager = durableTaskManager
                    ?: return@withContext ConnectorOutput.Failure(
                        code = "approval_runtime_unavailable",
                        message = "GitHub mutation cannot run because durable approval storage is unavailable"
                    )
                if (execution?.continuationId != null) {
                    val authorized = execution.isComplete && manager.isClaimedConnectorContinuation(
                        continuationId = execution.continuationId,
                        taskId = execution.taskId,
                        missionId = execution.missionId,
                        projectId = execution.projectId,
                        runId = execution.runId,
                        stepId = execution.stepId,
                        connectorId = id,
                        action = input.action,
                        idempotencyKey = execution.idempotencyKey
                    )
                    if (!authorized) {
                        return@withContext ConnectorOutput.Failure(
                            code = "invalid_approved_continuation",
                            message = "The approved GitHub action is stale, consumed, or does not match this task step"
                        )
                    }
                } else {
                    if (execution == null || !execution.isComplete || input.binary != null) {
                        return@withContext ConnectorOutput.Failure(
                            code = "approval_context_required",
                            message = "GitHub mutations require a task, project, run, step, and idempotency context before approval"
                        )
                    }
                    val approval = manager.requestApproval(
                        taskId = execution.taskId,
                        action = "github_${input.action}",
                        description = mutation.reason,
                        riskLevel = "HIGH",
                        runId = execution.runId,
                        stepId = execution.stepId
                    ) ?: return@withContext ConnectorOutput.Failure(
                        code = "approval_context_rejected",
                        message = "GitHub mutation could not bind approval to the active task step"
                    )
                    val continuation = ApprovalContinuation(
                        approvalId = approval.id,
                        taskId = execution.taskId,
                        missionId = execution.missionId,
                        projectId = execution.projectId,
                        runId = execution.runId,
                        stepId = execution.stepId,
                        invocation = ResumableConnectorInvocation(
                            connectorId = id,
                            action = input.action,
                            text = input.text,
                            params = input.params,
                            idempotencyKey = execution.idempotencyKey
                        ),
                        expiresAtMs = approval.expiresAtMs
                    )
                    if (!manager.pauseForApproval(execution.taskId, approval.id, continuation)) {
                        manager.decideApproval(
                            approval.id,
                            com.airi.assistant.agent.durable.TaskApprovalStatus.DENIED,
                            reason = "Continuation persistence rejected"
                        )
                        return@withContext ConnectorOutput.Failure(
                            code = "approval_continuation_rejected",
                            message = "GitHub mutation was not queued because its continuation failed validation"
                        )
                    }
                    AgentActivityBus.emit("GitHub action awaiting approval", ActivityCategory.CONNECTOR)
                    return@withContext ConnectorOutput.ApprovalRequired(
                        approvalId = approval.id,
                        taskId = execution.taskId,
                        runId = execution.runId,
                        stepId = execution.stepId,
                        expiresAtMs = approval.expiresAtMs,
                        message = mutation.reason
                    )
                }
            }
        }
        val execution = input.execution
        if (!execution?.projectId.isNullOrBlank()) {
            return@withContext executeWithProjectSecret(input, execution!!)
        }
        val token = legacyCredentialProvider(id, "pat")
            ?: return@withContext ConnectorOutput.Failure("not_connected", "GitHub PAT not configured")
        executeWithToken(input, token)
    }

    /**
     * A project execution never falls back to ConnectorAuthManager. The adapter
     * issues and consumes a one-use, project/connector-bound vault capability
     * only after the durable task/run/step coordinates match persisted state.
     */
    private fun executeWithProjectSecret(
        input: ConnectorInput,
        execution: ConnectorExecutionContext
    ): ConnectorOutput {
        val projectId = execution.projectId ?: return ConnectorOutput.Failure(
            "project_secret_context_missing", "GitHub project secret requires a project execution context"
        )
        if (!execution.isComplete || !projectExecutionOwnership(execution)) {
            return ConnectorOutput.Failure(
                "project_secret_context_rejected",
                "GitHub project secret does not match a persisted task run and step"
            )
        }
        val vault = secretVault ?: return ConnectorOutput.Failure(
            "project_secret_broker_unavailable", "GitHub project secret broker is unavailable"
        )
        val operation = "$id.${input.action}"
        val capability = vault.issueCapability(
            agentId = "connector.$id.${execution.taskId}",
            keyName = GITHUB_PAT_SECRET_ID,
            operation = operation,
            authorizedByPolicy = true,
            taskId = execution.taskId,
            uses = 1,
            projectId = projectId,
            connectorId = id
        ) ?: return ConnectorOutput.Failure(
            "project_secret_missing", "No project-scoped GitHub credential is available for this task"
        )
        val result = vault.useProjectCapability(
            token = capability.token,
            agentId = "connector.$id.${execution.taskId}",
            operation = operation,
            projectId = projectId,
            connectorId = id,
            consumer = { token -> executeWithToken(input, token) }
        )
        return when (result.status) {
            SecretVault.CapabilityStatus.CONSUMED -> result.value ?: ConnectorOutput.Failure(
                "project_secret_consumer_failed", "GitHub project credential could not complete the request"
            )
            SecretVault.CapabilityStatus.MISSING_SECRET -> ConnectorOutput.Failure(
                "project_secret_missing", "GitHub project credential is no longer available"
            )
            else -> ConnectorOutput.Failure(
                "project_secret_denied", "GitHub project credential access was denied or expired"
            )
        }
    }

    /** Raw PAT remains within this adapter and is never returned to an agent or continuation. */
    private fun executeWithToken(input: ConnectorInput, token: String): ConnectorOutput {
        tokenExecutor?.let { return it(input, token) }
        return try {
        val t0 = System.currentTimeMillis()
        val result = when (input.action) {
            "list_repos" -> listRepos(token)
            "list_issues" -> listIssues(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"))
            "create_issue" -> createIssue(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.text, input.params["body"] ?: "")
            "search_code" -> searchCode(token, input.text, input.params["repo"])
            "get_file" -> getFile(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["path"] ?: return ConnectorOutput.Failure("missing_param", "path required"))
            "list_prs" -> listPRs(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"))
            "status" -> return ConnectorOutput.Success(_state.value.statusLine)
            else -> return ConnectorOutput.Failure("unknown_action", "Unknown action: ${input.action}")
        }
        AgentActivityBus.emit("GitHub: ${input.action}", ActivityCategory.CONNECTOR)
        ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
    } catch (e: Exception) {
        ConnectorOutput.Failure("api_error", e.message ?: "Error", retryable = true)
    }
    }

    private fun listRepos(token: String): String {
        val all = apiGetAllPages("/user/repos?sort=updated&per_page=100", token, maxPages = 5)
        return buildString {
            appendLine("Repos (${all.length()}):")
            for (i in 0 until all.length()) {
                val r = all.getJSONObject(i)
                appendLine("• ${r.getString("full_name")} [${r.optString("language","?")}]")
            }
        }
    }
    private fun listIssues(token: String, repo: String): String { val arr = apiGetArr("/repos/$repo/issues?state=open&per_page=20", token); return buildString { appendLine("Open issues in $repo (${arr.length()}):"); for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); appendLine("#${x.getInt("number")}: ${x.getString("title")}") } } }
    private fun createIssue(token: String, repo: String, title: String, body: String): String { val r = apiPost("/repos/$repo/issues", token, JSONObject().apply { put("title",title);put("body",body) }.toString()); return "Issue #${r.optInt("number")} created: ${r.optString("html_url")}" }
    private fun searchCode(token: String, query: String, repo: String?): String { val q = if (repo != null) "$query repo:$repo" else query; val j = apiGet("/search/code?q=${java.net.URLEncoder.encode(q,"UTF-8")}&per_page=10", token); val items = j.optJSONArray("items") ?: JSONArray(); return buildString { appendLine("Code hits (${items.length()}):"); for (i in 0 until items.length()) { val x = items.getJSONObject(i); appendLine("• ${x.getString("path")} in ${x.getJSONObject("repository").getString("full_name")}") } } }
    private fun getFile(token: String, repo: String, path: String): String { val j = apiGet("/repos/$repo/contents/$path", token); val enc = j.optString("content","").replace("\n",""); return String(android.util.Base64.decode(enc, android.util.Base64.DEFAULT), Charsets.UTF_8) }
    private fun listPRs(token: String, repo: String): String { val arr = apiGetArr("/repos/$repo/pulls?state=open&per_page=20", token); return buildString { appendLine("Open PRs in $repo (${arr.length()}):"); for (i in 0 until arr.length()) { val p = arr.getJSONObject(i); appendLine("#${p.getInt("number")}: ${p.getString("title")}") } } }

    /**
     * Fetch all pages of a paginated GitHub list endpoint by following
     * Link header `rel="next"` references. Stops after [maxPages] pages
     * to protect against infinite redirect chains.
     *
     * Returns the accumulated JSONArray containing entries from all pages.
     */
    private fun apiGetAllPages(
        path: String,
        token: String,
        maxPages: Int = 5
    ): JSONArray {
        val result = JSONArray()
        var nextUrl: String? = "$BASE$path"
        var pages = 0

        while (nextUrl != null && pages < maxPages) {
            val conn = (URL(nextUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }
            try {
                val body = conn.inputStream.bufferedReader().readText()
                val page = JSONArray(body)
                for (i in 0 until page.length()) result.put(page.getJSONObject(i))

                nextUrl = parseLinkNext(conn.getHeaderField("Link"))
                pages++
            } finally {
                conn.disconnect()
            }
        }
        Log.d(TAG, "apiGetAllPages fetched ${result.length()} items across $pages page(s) from $path")
        return result
    }

    /** Parse GitHub Link header and return the URL for rel="next", or null. */
    private fun parseLinkNext(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        for (part in linkHeader.split(",")) {
            val trimmed = part.trim()
            if (trimmed.contains("rel=\"next\"")) {
                val match = Regex("<([^>]+)>").find(trimmed)
                return match?.groupValues?.getOrNull(1)
            }
        }
        return null
    }

    private fun apiGet(path: String, token: String): JSONObject { val c = open("$BASE$path", token); val b = c.inputStream.bufferedReader().readText(); c.disconnect(); return JSONObject(b) }
    private fun apiGetArr(path: String, token: String): JSONArray { val c = open("$BASE$path", token); val b = c.inputStream.bufferedReader().readText(); c.disconnect(); return JSONArray(b) }
    private fun apiPost(path: String, token: String, json: String): JSONObject { val c = open("$BASE$path", token, "POST"); c.doOutput = true; c.outputStream.bufferedWriter().use { it.write(json) }; val b = c.inputStream.bufferedReader().readText(); c.disconnect(); return JSONObject(b) }
    private fun open(url: String, token: String, method: String = "GET") = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Authorization","Bearer $token"); setRequestProperty("Accept","application/vnd.github.v3+json"); setRequestProperty("Content-Type","application/json") }

    private companion object {
        const val GITHUB_PAT_SECRET_ID = "GITHUB_PAT"
    }
}
