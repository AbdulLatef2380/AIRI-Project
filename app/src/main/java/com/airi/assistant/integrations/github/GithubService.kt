package com.airi.assistant.integrations.github

import com.airi.assistant.ai.tools.ToolResult
import com.airi.assistant.auth.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GithubService(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── : Validate & connect ──────────────────────────────────────────

    suspend fun validateAndConnect(token: String): Result<String> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token cannot be empty"))
        }
        try {
            val response = get("https://api.github.com/user", token.trim())
            when {
                response.code == 200 -> {
                    val json = JSONObject(response.body?.string() ?: "")
                    val login = json.optString("login", "")
                    secureStorage.saveGithubToken(token.trim())
                    secureStorage.saveGithubConnected(true, "@$login")
                    Result.success("@$login")
                }
                response.code == 401 -> {
                    secureStorage.saveGithubConnected(false)
                    Result.failure(Exception("Invalid or expired token (401)"))
                }
                else -> {
                    val errorMsg = try {
                        JSONObject(response.body?.string() ?: "").optString("message", "Error ${response.code}")
                    } catch (e: Exception) { "Error ${response.code}" }
                    Result.failure(Exception(errorMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Connection failed: ${e.message}"))
        }
    }

    // ─── : API calls ────────────────────────────────────────────────────

    suspend fun getUser(): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "GitHub token not found. Please reconnect.")
        try {
            val response = get("https://api.github.com/user", token)
            when (response.code) {
                200 -> {
                    val json = JSONObject(response.body?.string() ?: "")
                    val login = json.optString("login")
                    val name = json.optString("name", login)
                    val bio = json.optString("bio", "")
                    val repos = json.optInt("public_repos", 0)
                    val followers = json.optInt("followers", 0)
                    val following = json.optInt("following", 0)
                    val data = buildString {
                        append("GitHub User: $name (@$login)\n")
                        if (bio.isNotBlank()) append("Bio: $bio\n")
                        append("Public repos: $repos | Followers: $followers | Following: $following")
                    }
                    ToolResult(true, data)
                }
                401 -> {
                    secureStorage.saveGithubConnected(false)
                    ToolResult(false, "", "GitHub token expired. Please reconnect in Integrations.")
                }
                else -> ToolResult(false, "", "GitHub API error: ${response.code}")
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Request failed: ${e.message}")
        }
    }

    /**
     * Fetch up to [limit] repositories, using Link-header pagination to bypass
     * the GitHub API's 30-item-per-page ceiling.
     *
     * For [limit] ≤ 30 a single request is issued (fast path).
     * For [limit] > 30 the function follows `rel="next"` Link headers until
     * [limit] items have been collected or the last page is reached.
     *
     * The GitHub API caps `per_page` at 100 per request. We use the minimum of
     * [limit] and 100 as the page size to minimise round-trips.
     */
    suspend fun getRepos(limit: Int = 10): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "GitHub token not found. Please reconnect.")

        val clampedLimit = limit.coerceIn(1, 300)
        val pageSize     = minOf(clampedLimit, 100)

        try {
            val collected = mutableListOf<JSONObject>()
            var nextUrl: String? =
                "https://api.github.com/user/repos?sort=updated&per_page=$pageSize"

            while (nextUrl != null && collected.size < clampedLimit) {
                val response = get(nextUrl, token)
                when (response.code) {
                    200 -> {
                        val body     = response.body?.string() ?: "[]"
                        val linkHdr  = response.header("Link")
                        val array    = JSONArray(body)
                        for (i in 0 until array.length()) {
                            if (collected.size >= clampedLimit) break
                            collected.add(array.getJSONObject(i))
                        }
                        nextUrl = parseLinkNext(linkHdr)
                    }
                    401 -> {
                        secureStorage.saveGithubConnected(false)
                        return@withContext ToolResult(
                            false, "", "GitHub token expired. Please reconnect in Integrations."
                        )
                    }
                    else -> return@withContext ToolResult(
                        false, "", "GitHub API error: ${response.code}"
                    )
                }
            }

            if (collected.isEmpty()) return@withContext ToolResult(true, "No repositories found.")

            val sb = StringBuilder("Your GitHub repositories (${collected.size}):\n")
            for (repo in collected) {
                val repoName = repo.optString("full_name")
                val desc     = repo.optString("description", "").let {
                    if (it.isNotBlank()) " — $it" else ""
                }
                val stars   = repo.optInt("stargazers_count", 0)
                val lang    = repo.optString("language", "")
                val private = if (repo.optBoolean("private")) " " else ""
                sb.append("• $repoName$private$desc")
                if (lang.isNotBlank()) sb.append(" [$lang]")
                if (stars > 0) sb.append(" ⭐$stars")
                sb.append("\n")
            }
            ToolResult(true, sb.toString().trimEnd())
        } catch (e: Exception) {
            ToolResult(false, "", "Request failed: ${e.message}")
        }
    }

    fun disconnect() = secureStorage.disconnect("github")

    // ─── Internal ─────────────────────────────────────────────────────────────

    /**
     * List branches for a repository.
     */
    suspend fun listBranches(owner: String, repo: String): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "No GitHub token — connect GitHub first")
        runCatching {
            val resp = get("https://api.github.com/repos/$owner/$repo/branches?per_page=30", token)
            if (!resp.isSuccessful) return@runCatching ToolResult(false, "", "GitHub API error: ${resp.code}")
            val arr = JSONArray(resp.body!!.string())
            val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
            ToolResult(true, "Branches: ${names.joinToString(", ")}")
        }.getOrElse { ToolResult(false, "", it.message ?: "Unknown error") }
    }

    /**
     * Get commit history for a branch.
     */
    suspend fun getCommitHistory(owner: String, repo: String, branch: String = "main", limit: Int = 20): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "No GitHub token — connect GitHub first")
        runCatching {
            val resp = get("https://api.github.com/repos/$owner/$repo/commits?sha=$branch&per_page=$limit", token)
            if (!resp.isSuccessful) return@runCatching ToolResult(false, "", "GitHub API error: ${resp.code}")
            val arr = JSONArray(resp.body!!.string())
            val commits = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val msg = obj.getJSONObject("commit").getString("message").lines().first().take(72)
                val sha = obj.getString("sha").take(7)
                "$sha $msg"
            }
            ToolResult(true, commits.joinToString("\n"))
        }.getOrElse { ToolResult(false, "", it.message ?: "Unknown error") }
    }

    /**
     * Create or update a file (commit) in a repository.
     */
    suspend fun createCommit(
        owner: String, repo: String,
        path: String, content: String,
        message: String, branch: String = "main"
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "No GitHub token — connect GitHub first")
        runCatching {
            val existingSha: String? = runCatching {
                val r = get("https://api.github.com/repos/$owner/$repo/contents/$path?ref=$branch", token)
                if (r.isSuccessful) JSONObject(r.body!!.string()).getString("sha") else null
            }.getOrNull()

            val encoded = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val body = JSONObject().apply {
                put("message", message)
                put("content", encoded)
                put("branch", branch)
                if (existingSha != null) put("sha", existingSha)
            }
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/contents/$path")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .put(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) ToolResult(true, "Committed '$path' to $branch")
            else ToolResult(false, "", "GitHub API error ${resp.code}: ${resp.body?.string()?.take(200)}")
        }.getOrElse { ToolResult(false, "", it.message ?: "Unknown error") }
    }

    /**
     * Create a pull request.
     */
    suspend fun createPullRequest(
        owner: String, repo: String,
        title: String, body: String,
        head: String, base: String = "main"
    ): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getGithubToken()
            ?: return@withContext ToolResult(false, "", "No GitHub token — connect GitHub first")
        runCatching {
            val payload = JSONObject().apply {
                put("title", title)
                put("body",  body)
                put("head",  head)
                put("base",  base)
            }
            val request = okhttp3.Request.Builder()
                .url("https://api.github.com/repos/$owner/$repo/pulls")
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/vnd.github.v3+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) {
                val url = JSONObject(resp.body!!.string()).getString("html_url")
                ToolResult(true, "PR created: $url")
            } else ToolResult(false, "", "GitHub API error ${resp.code}: ${resp.body?.string()?.take(200)}")
        }.getOrElse { ToolResult(false, "", it.message ?: "Unknown error") }
    }

    private fun get(url: String, token: String): Response = client.newCall(
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
    ).execute()

    /**
     * Parse the `rel="next"` URL from a GitHub `Link` response header.
     *
     * Example header value:
     *   <https://api.github.com/user/repos?page=2>; rel="next",
     *   <https://api.github.com/user/repos?page=5>; rel="last"
     *
     * Returns null when there is no next page.
     */
    private fun parseLinkNext(linkHeader: String?): String? {
        if (linkHeader.isNullOrBlank()) return null
        return linkHeader.split(",")
            .map { it.trim() }
            .firstOrNull { it.contains("""rel="next"""") }
            ?.let { segment ->
                val start = segment.indexOf('<') + 1
                val end   = segment.indexOf('>')
                if (start > 0 && end > start) segment.substring(start, end) else null
            }
    }
}
