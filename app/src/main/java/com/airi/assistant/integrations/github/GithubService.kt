package com.airi.assistant.integrations.github

import com.airi.assistant.ai.tools.ToolResult
import com.airi.assistant.auth.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class GithubService(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── Phase 1: Validate & connect ──────────────────────────────────────────

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

    // ─── Phase 2: API calls ────────────────────────────────────────────────────

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
                val private = if (repo.optBoolean("private")) " 🔒" else ""
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
