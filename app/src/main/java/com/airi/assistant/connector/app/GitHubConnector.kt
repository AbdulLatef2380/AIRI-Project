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
    override fun agentActions() = listOf(
        ConnectorAgentAction("get_user", "Read the authenticated GitHub user's profile.", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.PROVIDER_ENDPOINT_PERMISSION, "Authorized GitHub token with access to GET /user.")
        )),
        ConnectorAgentAction("list_repos", "List repositories available to the authorized GitHub account.", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Metadata: read")
        ), parameters = mapOf(
            "limit" to ConnectorAgentParameter(type = "int", description = "Maximum repositories to return", minInt = 1, maxInt = 100)
        )),
        ConnectorAgentAction("list_branches", "List branches for a repository.", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Contents: read")
        ), parameters = mapOf(
            "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true)
        )),
        ConnectorAgentAction("list_commits", "List recent commits for a repository branch.", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Contents: read")
        ), parameters = mapOf(
            "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
            "branch" to ConnectorAgentParameter(description = "Branch name", maxLength = 256),
            "limit" to ConnectorAgentParameter(type = "int", description = "Maximum commits to return", minInt = 1, maxInt = 100)
        )),
        ConnectorAgentAction("list_issues", "List issues for a repository.", parameters = mapOf(
            "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true)
        ), providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Issues: read"))),
        ConnectorAgentAction("search_code", "Search code in authorized repositories.", parameters = mapOf(
            "query" to ConnectorAgentParameter(description = "Code search query", required = true),
            "repo" to ConnectorAgentParameter(description = "Optional owner/name repository")
        ), providerGrants = listOf(ConnectorProviderGrant(
            ConnectorProviderGrantKind.PROVIDER_ENDPOINT_PERMISSION,
            "Authenticated token with access to the queried repositories; GitHub code-search token compatibility/permissions must be verified from the endpoint response.",
        ))),
        ConnectorAgentAction("get_file", "Read a file from a repository.", parameters = mapOf(
            "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
            "path" to ConnectorAgentParameter(description = "Repository file path", required = true)
        ), providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Contents: read"))),
        ConnectorAgentAction("list_prs", "List pull requests for a repository.", parameters = mapOf(
            "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true)
        ), providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Pull requests: read"))),
        ConnectorAgentAction("status", "Return the current project-scoped GitHub authorization and runtime status without exposing credentials.", providerGrants = listOf(ConnectorProviderGrant(
            ConnectorProviderGrantKind.PROVIDER_ENDPOINT_PERMISSION,
            "A configured Broker runtime and a verified project-scoped GitHub session; no PAT fallback is permitted.",
        ))),
        ConnectorAgentAction("create_issue", "Create a repository issue after explicit task approval.",
            permission = ConnectorPermissionLevel.WRITE, supportsApprovedContinuation = true,
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Issues: write")),
            parameters = mapOf(
                "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
                "title" to ConnectorAgentParameter(description = "Issue title", required = true, maxLength = 256),
                "body" to ConnectorAgentParameter(description = "Issue body", maxLength = 20_000),
            )),
        ConnectorAgentAction("comment_issue", "Comment on an issue after explicit task approval.",
            permission = ConnectorPermissionLevel.WRITE, supportsApprovedContinuation = true,
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Issues: write")),
            parameters = mapOf(
                "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
                "issue_number" to ConnectorAgentParameter(type = "int", description = "Issue number", required = true, minInt = 1),
                "body" to ConnectorAgentParameter(description = "Comment body", required = true, maxLength = 20_000),
            )),
        ConnectorAgentAction("update_issue", "Update an issue after explicit task approval.",
            permission = ConnectorPermissionLevel.WRITE, supportsApprovedContinuation = true,
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Issues: write")),
            parameters = mapOf(
                "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
                "issue_number" to ConnectorAgentParameter(type = "int", description = "Issue number", required = true, minInt = 1),
                "title" to ConnectorAgentParameter(description = "New issue title", maxLength = 256),
                "body" to ConnectorAgentParameter(description = "New issue body", maxLength = 20_000),
                "state" to ConnectorAgentParameter(description = "open or closed", maxLength = 16),
            )),
        ConnectorAgentAction("create_pull_request", "Create a pull request after explicit task approval.",
            permission = ConnectorPermissionLevel.WRITE, supportsApprovedContinuation = true,
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION, "Pull requests: write")),
            parameters = mapOf(
                "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true),
                "title" to ConnectorAgentParameter(description = "Pull request title", required = true, maxLength = 256),
                "head" to ConnectorAgentParameter(description = "Source branch", required = true, maxLength = 256),
                "base" to ConnectorAgentParameter(description = "Target branch", required = true, maxLength = 256),
                "body" to ConnectorAgentParameter(description = "Pull request body", maxLength = 20_000),
            )),
        ConnectorAgentAction("push", "Create or update a repository file after explicit approval.",
            permission = ConnectorPermissionLevel.WRITE, supportsApprovedContinuation = true,
            providerGrants = listOf(ConnectorProviderGrant(
                ConnectorProviderGrantKind.GITHUB_FINE_GRAINED_TOKEN_PERMISSION,
                "Contents: write",
            )),
            parameters = mapOf(
                "repo" to ConnectorAgentParameter(description = "owner/name repository", required = true, maxLength = 128),
                "path" to ConnectorAgentParameter(description = "Repository file path", required = true, maxLength = 512),
                // Durable approval continuations persist bounded parameter values;
                // larger files must use the dedicated project-file flow instead.
                "content" to ConnectorAgentParameter(description = "New file content (up to 4 KiB in an approved connector task)", required = true, maxLength = 4_096),
                "message" to ConnectorAgentParameter(description = "Commit message", required = true, maxLength = 256),
                "branch" to ConnectorAgentParameter(description = "Target branch", maxLength = 256),
                "sha" to ConnectorAgentParameter(description = "Current blob SHA when updating an existing file", maxLength = 128),
            )),
    )
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        // A legacy global PAT is not evidence of a project-scoped Broker session.
        // Keep the connector unavailable until a real Broker-backed runtime adapter is installed.
        _state.value = ConnectorState(
            connected = false,
            healthy = false,
            statusLine = "Project GitHub Broker runtime unavailable",
            lastUpdatedMs = System.currentTimeMillis(),
            errorMessage = "GitHub actions require a configured project-scoped authorization Broker",
        )
        _state.value
    }

    override suspend fun disconnect() {
        authManager?.clearCredential(id, "pat")
        _state.value = ConnectorState(false, statusLine = "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!LEGACY_PROJECT_PAT_EXECUTION_ENABLED) {
            return@withContext ConnectorOutput.Failure(
                code = "github_broker_not_configured",
                message = "GitHub actions are unavailable until a project-scoped Broker runtime is configured; PAT fallback is disabled",
            )
        }
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
                            idempotencyKey = execution.idempotencyKey,
                            params = input.params,
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
                            idempotencyKey = execution.idempotencyKey,
                            authorizationActionId = input.authorizationActionId ?: input.action,
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
        // GitHub access is never inferred from the process-wide credential
        // namespace.  A request without an AIRI project cannot be bound to a
        // tenant/repository grant, so failing here is safer than falling back
        // to a developer or legacy PAT.
        ConnectorOutput.Failure(
            code = "project_context_required",
            message = "GitHub access requires a project-scoped authorization"
        )
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
        if (input.action == "push") validatePushParams(input.params)
        tokenExecutor?.let { return it(input, token) }
        return try {
        val t0 = System.currentTimeMillis()
        val result = when (input.action) {
            "get_user" -> getUser(token)
            "list_repos" -> listRepos(token, input.params["limit"]?.toIntOrNull() ?: 10)
            "list_branches" -> listBranches(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"))
            "list_commits" -> listCommits(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["branch"] ?: "main", input.params["limit"]?.toIntOrNull() ?: 20)
            "list_issues" -> listIssues(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"))
            "create_issue" -> createIssue(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["title"] ?: return ConnectorOutput.Failure("missing_param", "title required"), input.params["body"] ?: "")
            "comment_issue" -> commentIssue(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["issue_number"]?.toIntOrNull() ?: return ConnectorOutput.Failure("missing_param", "issue_number required"), input.params["body"] ?: return ConnectorOutput.Failure("missing_param", "body required"))
            "update_issue" -> updateIssue(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["issue_number"]?.toIntOrNull() ?: return ConnectorOutput.Failure("missing_param", "issue_number required"), input.params)
            "create_pull_request" -> createPullRequest(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["title"] ?: return ConnectorOutput.Failure("missing_param", "title required"), input.params["head"] ?: return ConnectorOutput.Failure("missing_param", "head required"), input.params["base"] ?: return ConnectorOutput.Failure("missing_param", "base required"), input.params["body"] ?: "")
            "push" -> pushFile(token, input.params)
            "search_code" -> searchCode(token, input.params["query"] ?: return ConnectorOutput.Failure("missing_param", "query required"), input.params["repo"])
            "get_file" -> getFile(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"), input.params["path"] ?: return ConnectorOutput.Failure("missing_param", "path required"))
            "list_prs" -> listPRs(token, input.params["repo"] ?: return ConnectorOutput.Failure("missing_param", "repo required"))
            "status" -> return ConnectorOutput.Success(_state.value.statusLine)
            else -> return ConnectorOutput.Failure("unknown_action", "Unknown action: ${input.action}")
        }
        AgentActivityBus.emit("GitHub: ${input.action}", ActivityCategory.CONNECTOR)
        ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
        } catch (e: GitHubApiHttpException) {
            GitHubApiFailurePolicy.map(e.statusCode, e.rateLimitRemaining, input.action)
        } catch (e: IllegalArgumentException) {
            ConnectorOutput.Failure("invalid_params", e.message ?: "Invalid GitHub parameters", retryable = false)
        } catch (_: Exception) {
            ConnectorOutput.Failure("provider_error", "GitHub request could not be completed", retryable = true)
        }
    }

    private fun getUser(token: String): String {
        val user = apiGet("/user", token)
        return buildString {
            appendLine("GitHub profile: @${user.optString("login", "unknown")}")
            user.optString("name").takeIf(String::isNotBlank)?.let { appendLine("Name: $it") }
            user.optString("bio").takeIf(String::isNotBlank)?.let { appendLine("Bio: ${it.take(500)}") }
            appendLine("Public repositories: ${user.optInt("public_repos")}")
            appendLine("Followers: ${user.optInt("followers")}; following: ${user.optInt("following")}")
            user.optString("html_url").takeIf(String::isNotBlank)?.let { append("Profile: $it") }
        }
    }

    private fun listRepos(token: String, limit: Int): String {
        val boundedLimit = limit.coerceIn(1, 100)
        val all = apiGetAllPages("/user/repos?sort=updated&per_page=$boundedLimit", token, maxPages = 1)
        return buildString {
            appendLine("Repos (${all.length()}):")
            for (i in 0 until all.length()) {
                val r = all.getJSONObject(i)
                appendLine("• ${r.getString("full_name")} [${r.optString("language","?")}]")
            }
        }
    }
    private fun listBranches(token: String, repo: String): String {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid repository owner/name" }
        val branches = apiGetAllPages("/repos/$repo/branches?per_page=100", token, maxPages = 1)
        return (0 until branches.length()).joinToString("\n") { i -> branches.getJSONObject(i).optString("name") }
    }
    private fun listCommits(token: String, repo: String, branch: String, limit: Int): String {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid repository owner/name" }
        require(branch.isNotBlank() && branch.length <= 256) { "Invalid branch" }
        val boundedLimit = limit.coerceIn(1, 100)
        val commits = apiGetArr("/repos/$repo/commits?sha=${java.net.URLEncoder.encode(branch, "UTF-8")}&per_page=$boundedLimit", token)
        return (0 until commits.length()).joinToString("\n") { i ->
            val item = commits.getJSONObject(i)
            val message = item.optJSONObject("commit")?.optString("message").orEmpty()
                .lineSequence().firstOrNull().orEmpty().take(72)
            "${item.optString("sha").take(7)} $message"
        }
    }
    private fun listIssues(token: String, repo: String): String { val arr = apiGetArr("/repos/$repo/issues?state=open&per_page=20", token); return buildString { appendLine("Open issues in $repo (${arr.length()}):"); for (i in 0 until arr.length()) { val x = arr.getJSONObject(i); appendLine("#${x.getInt("number")}: ${x.getString("title")}") } } }
    private fun createIssue(token: String, repo: String, title: String, body: String): String { val r = apiPost("/repos/$repo/issues", token, JSONObject().apply { put("title",title);put("body",body) }.toString()); return "Issue #${r.optInt("number")} created: ${r.optString("html_url")}" }
    private fun commentIssue(token: String, repo: String, number: Int, body: String): String {
        require(number > 0 && body.isNotBlank()) { "issue_number and body are required" }
        val r = apiPost("/repos/$repo/issues/$number/comments", token, JSONObject().put("body", body).toString())
        return "Comment added to issue #$number: ${r.optString("html_url")}".trim()
    }
    private fun updateIssue(token: String, repo: String, number: Int, params: Map<String, String>): String {
        require(number > 0) { "issue_number must be positive" }
        val payload = JSONObject()
        params["title"]?.takeIf(String::isNotBlank)?.let { payload.put("title", it) }
        params["body"]?.let { payload.put("body", it) }
        params["state"]?.takeIf(String::isNotBlank)?.also { require(it == "open" || it == "closed") { "state must be open or closed" } }?.let { payload.put("state", it) }
        require(payload.length() > 0) { "at least one issue field is required" }
        val r = apiPatch("/repos/$repo/issues/$number", token, payload.toString())
        return "Issue #$number updated: ${r.optString("html_url")}".trim()
    }
    private fun createPullRequest(token: String, repo: String, title: String, head: String, base: String, body: String): String {
        val r = apiPost("/repos/$repo/pulls", token, JSONObject().apply { put("title", title); put("head", head); put("base", base); put("body", body) }.toString())
        return "Pull request #${r.optInt("number")} created: ${r.optString("html_url")}".trim()
    }
    private fun pushFile(token: String, params: Map<String, String>): String {
        validatePushParams(params)
        val repo = params["repo"].orEmpty()
        val path = params["path"].orEmpty()
        val content = params["content"] ?: error("File content is missing")
        val message = params["message"].orEmpty()
        val branch = params["branch"].orEmpty().ifBlank { "main" }
        val sha = params["sha"].orEmpty()
        val encodedPath = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val payload = JSONObject().apply {
            put("message", message)
            put("content", android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))
            put("branch", branch)
            if (sha.isNotBlank()) put("sha", sha)
        }
        val response = apiPut("/repos/$repo/contents/$encodedPath", token, payload.toString())
        val commit = response.optJSONObject("commit")
        val commitUrl = commit?.optString("html_url").orEmpty()
        val commitSha = commit?.optString("sha").orEmpty()
        require(commitSha.isNotBlank()) { "GitHub did not return a commit SHA" }
        return buildString {
            append("GitHub commit created: ")
            append(commitUrl.ifBlank { "unavailable" })
            append("\nSHA: ")
            append(commitSha)
        }
    }
    private fun validatePushParams(params: Map<String, String>) {
        val repo = params["repo"].orEmpty()
        val path = params["path"].orEmpty()
        val content = params["content"]
        val message = params["message"].orEmpty()
        val branch = params["branch"].orEmpty().ifBlank { "main" }
        val sha = params["sha"].orEmpty()
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "Invalid repository owner/name" }
        require(path.isNotBlank() && path.length <= 512 && !path.startsWith("/") && !path.endsWith("/")) { "Invalid repository file path" }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." } && path.none { it.code < 32 }) { "Invalid repository file path" }
        require(content != null && content.length <= 4_096) { "File content is missing or exceeds the approved connector limit" }
        require(message.isNotBlank() && message.length <= 256) { "Commit message is required and must be at most 256 characters" }
        require(branch.length <= 256 && branch.none { it.code < 32 }) { "Invalid branch" }
        if (sha.isNotBlank()) require(sha.length <= 128 && sha.all { it.isLetterOrDigit() || it in "._-" }) { "Invalid blob SHA" }
    }
    private fun searchCode(token: String, query: String, repo: String?): String { val q = if (repo != null) "$query repo:$repo" else query; val j = apiGet("/search/code?q=${java.net.URLEncoder.encode(q,"UTF-8")}&per_page=10", token); val items = j.optJSONArray("items") ?: JSONArray(); return buildString { appendLine("Code hits (${items.length()}):"); for (i in 0 until items.length()) { val x = items.getJSONObject(i); appendLine("• ${x.getString("path")} in ${x.getJSONObject("repository").getString("full_name")}") } } }
    private fun getFile(token: String, repo: String, path: String): String {
        if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            throw IllegalArgumentException("Invalid repository owner/name")
        }
        if (path.isBlank() || path.startsWith("/") || path.length > 512) {
            throw IllegalArgumentException("Invalid repository file path")
        }
        // Encode each path segment, not the slash separators. Without this,
        // spaces, '#', '?' and non-ASCII filenames are sent as malformed URLs.
        val encodedPath = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20")
        }
        val j = apiGet("/repos/$repo/contents/$encodedPath", token)
        val enc = j.optString("content", "").replace("\n", "")
        require(enc.isNotBlank()) { "GitHub returned a directory or an empty file response" }
        return String(android.util.Base64.decode(enc, android.util.Base64.DEFAULT), Charsets.UTF_8)
    }
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
                val body = readResponse(conn)
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
                val candidate = match?.groupValues?.getOrNull(1) ?: return null
                val parsed = runCatching { URL(candidate) }.getOrNull() ?: return null
                if (parsed.protocol != "https" || parsed.host != "api.github.com") return null
                return candidate
            }
        }
        return null
    }

    private fun apiGet(path: String, token: String): JSONObject {
        val connection = open("$BASE$path", token)
        return try { JSONObject(readResponse(connection)) } finally { connection.disconnect() }
    }
    private fun apiGetArr(path: String, token: String): JSONArray {
        val connection = open("$BASE$path", token)
        return try { JSONArray(readResponse(connection)) } finally { connection.disconnect() }
    }
    private fun apiPost(path: String, token: String, json: String): JSONObject {
        val connection = open("$BASE$path", token, "POST")
        return try {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(json) }
            JSONObject(readResponse(connection))
        } finally { connection.disconnect() }
    }
    private fun apiPatch(path: String, token: String, json: String): JSONObject {
        val connection = open("$BASE$path", token, "PATCH")
        return try {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(json) }
            JSONObject(readResponse(connection))
        } finally { connection.disconnect() }
    }

    /** Read success/error streams uniformly; never copy provider bodies into user-facing errors. */
    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw GitHubApiHttpException(
                statusCode = status,
                rateLimitRemaining = connection.getHeaderField("X-RateLimit-Remaining")?.toLongOrNull(),
            )
        }
        return body
    }
    private fun open(url: String, token: String, method: String = "GET") = (URL(url).openConnection() as HttpURLConnection).apply { requestMethod = method; connectTimeout = 10_000; readTimeout = 15_000; setRequestProperty("Authorization","Bearer $token"); setRequestProperty("Accept","application/vnd.github.v3+json"); setRequestProperty("Content-Type","application/json") }

    private fun apiPut(path: String, token: String, json: String): JSONObject {
        val connection = open("$BASE$path", token, "PUT")
        return try {
            connection.doOutput = true
            connection.outputStream.bufferedWriter().use { it.write(json) }
            JSONObject(readResponse(connection))
        } finally { connection.disconnect() }
    }

    private companion object {
        const val GITHUB_PAT_SECRET_ID = "GITHUB_PAT"
        const val LEGACY_PROJECT_PAT_EXECUTION_ENABLED = false
    }
}
