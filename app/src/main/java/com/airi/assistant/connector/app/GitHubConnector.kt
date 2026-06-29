package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GitHubConnector(private val authManager: ConnectorAuthManager) : Connector {
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
        val token = authManager.getCredential(id, "pat")
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
        val token = authManager.getCredential(id, "pat")
            ?: return@withContext ConnectorOutput.Failure("not_connected", "GitHub PAT not configured")
        try {
            val t0 = System.currentTimeMillis()
            val result = when (input.action) {
                "list_repos"   -> listRepos(token)
                "list_issues"  -> listIssues(token, input.params["repo"] ?: return@withContext ConnectorOutput.Failure("missing_param", "repo required"))
                "create_issue" -> createIssue(token, input.params["repo"] ?: return@withContext ConnectorOutput.Failure("missing_param", "repo required"), input.text, input.params["body"] ?: "")
                "search_code"  -> searchCode(token, input.text, input.params["repo"])
                "get_file"     -> getFile(token, input.params["repo"] ?: return@withContext ConnectorOutput.Failure("missing_param","repo required"), input.params["path"] ?: return@withContext ConnectorOutput.Failure("missing_param","path required"))
                "list_prs"     -> listPRs(token, input.params["repo"] ?: return@withContext ConnectorOutput.Failure("missing_param","repo required"))
                "status"       -> return@withContext ConnectorOutput.Success(_state.value.statusLine)
                else           -> return@withContext ConnectorOutput.Failure("unknown_action", "Unknown action: ${input.action}")
            }
            AgentActivityBus.emit("GitHub: ${input.action}", ActivityCategory.CONNECTOR)
            ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) { ConnectorOutput.Failure("api_error", e.message ?: "Error", retryable = true) }
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
}
