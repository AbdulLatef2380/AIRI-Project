package com.airi.assistant.agent.subagent.impl

import android.content.Context
import android.net.Uri
import android.util.Log
import com.airi.assistant.agent.browser.BrowserNavigationPolicy
import com.airi.assistant.agent.browser.BrowserUserTakeoverCoordinator
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * LocalBrowserOperator — on-device browser control via Android Intents.
 *
 * USER-CONTROLLED HANDOFF:
 *   - Resolves URLs, searches, and deep links for the device browser.
 *   - Never launches an external app autonomously. Every handoff emits an
 *     explicit user-takeover event before a user-controlled browser action.
 *   - Supports deep-link navigation for common services (YouTube, Maps,
 *     Wikipedia, GitHub, Reddit, Twitter/X, etc.).
 *   - Falls back to a plain HTTPS open when no deep-link matches.
 *   - Operates 100% on-device — no outbound HTTP from the agent itself.
 *
 * PRIVACY:
 *   - No data leaves the device from this agent's perspective.
 *   - The user's browser handles cookies and tracking independently.
 *   - Works in PRIVACY_MAXIMUM mode (local-only operation).
 */
class LocalBrowserOperator(
    private val context: Context
) : SubAgent {

    companion object {
        private const val TAG = "LocalBrowserOperator"

        internal fun handoffDecision(rawUri: String): BrowserNavigationPolicy.Decision {
            val scheme = runCatching { java.net.URI(rawUri).scheme?.lowercase() }.getOrNull()
            return when (scheme) {
                "http", "https" -> BrowserNavigationPolicy.evaluate(
                    rawUri,
                    BrowserNavigationPolicy.Operation.OPEN_EXTERNAL
                )
                "geo" -> BrowserNavigationPolicy.Decision.RequiresUserTakeover(
                    rawUri,
                    "Opening a maps application transfers control to the user"
                )
                else -> BrowserNavigationPolicy.Decision.Blocked("Unsupported external navigation URI")
            }
        }
    }

    override val capability = SubAgentCapability(
        agentId        = "local_browser_operator",
        displayName    = "Local Browser",
        description    = "Prepare safe, user-controlled browser handoffs for websites and searches.",
        intentKeywords = listOf(
            "open browser", "open in browser", "open link", "launch browser",
            "navigate to", "take me to", "show me on maps", "search on youtube",
            "find on youtube", "look up on google", "open google maps",
            "open wikipedia", "open github", "play on youtube"
        ),
        domains             = listOf("browser", "navigation", "link", "local", "intent"),
        requiresCloud       = false,
        requiredTools       = listOf("android_intent"),
        costTier            = SubAgentCapability.CostTier.FREE,
        latencyProfile      = SubAgentCapability.LatencyProfile.INSTANT,
        supportsBackground  = false,
        maxParallelSubTasks = 1,
        supportsResume      = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        val lower = input.lowercase()
        return LOCAL_BROWSER_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "LOCAL_BROWSER_EXECUTE inputChars=${input.length}")

        emit(AgentEvent.Progress("Resolving navigation intent…", 15, "resolve"))

        val action = detectBrowserAction(input.lowercase())
        val handoff = handoffDecision(action.uri.toString())

        when (handoff) {
            is BrowserNavigationPolicy.Decision.RequiresUserTakeover -> {
                val request = BrowserUserTakeoverCoordinator.request(
                    rawUrl = handoff.normalizedUrl,
                    reason = handoff.reason
                ) ?: run {
                    emit(AgentEvent.Failed("Browser handoff could not be safely presented", recoverable = false))
                    return@flow
                }
                emit(AgentEvent.ToolCall(
                    toolName = "browser_user_takeover",
                    params = mapOf("request_id" to request.id, "url" to request.normalizedUrl),
                    reasoning = "External browser navigation requires user control"
                ))
                emit(AgentEvent.PartialResult(
                    "This browser action needs you to take control: ${request.reason}",
                    isFinal = true
                ))
                emit(AgentEvent.Complete(
                    result = "[LocalBrowser: user takeover required for ${action.label}]",
                    durationMs = System.currentTimeMillis() - start,
                    toolsUsed = listOf("browser_user_takeover")
                ))
            }
            is BrowserNavigationPolicy.Decision.Blocked -> {
                emit(AgentEvent.Failed("Browser navigation blocked: ${handoff.reason}", recoverable = false))
            }
            else -> {
                emit(AgentEvent.Failed("Browser handoff policy returned an unsupported decision", recoverable = false))
            }
        }
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private data class BrowserAction(val label: String, val uri: Uri)

    private fun detectBrowserAction(lower: String): BrowserAction {
        // Deep-link routing for common services
        return when {
            lower.contains("youtube") -> {
                val query = extractQuery(lower, "youtube")
                if (query.isNotBlank()) {
                    val enc = Uri.encode(query)
                    BrowserAction("YouTube search: $query",
                        Uri.parse("https://www.youtube.com/results?search_query=$enc"))
                } else {
                    BrowserAction("YouTube", Uri.parse("https://www.youtube.com"))
                }
            }
            lower.contains("maps") || lower.contains("directions") -> {
                val query = extractQuery(lower, "maps", "directions")
                val enc   = Uri.encode(query.ifBlank { lower })
                BrowserAction("Google Maps: $query",
                    Uri.parse("geo:0,0?q=$enc"))
            }
            lower.contains("wikipedia") -> {
                val query = extractQuery(lower, "wikipedia")
                val enc   = Uri.encode(query.ifBlank { lower })
                BrowserAction("Wikipedia: $query",
                    Uri.parse("https://en.wikipedia.org/w/index.php?search=$enc"))
            }
            lower.contains("github") -> {
                val query = extractQuery(lower, "github")
                if (query.isNotBlank()) {
                    val enc = Uri.encode(query)
                    BrowserAction("GitHub search: $query",
                        Uri.parse("https://github.com/search?q=$enc"))
                } else {
                    BrowserAction("GitHub", Uri.parse("https://github.com"))
                }
            }
            lower.contains("reddit") -> {
                val query = extractQuery(lower, "reddit")
                if (query.isNotBlank()) {
                    val enc = Uri.encode(query)
                    BrowserAction("Reddit search: $query",
                        Uri.parse("https://www.reddit.com/search/?q=$enc"))
                } else {
                    BrowserAction("Reddit", Uri.parse("https://www.reddit.com"))
                }
            }
            lower.contains("http://") || lower.contains("https://") -> {
                val url = extractRawUrl(lower)
                BrowserAction("Open $url", Uri.parse(url))
            }
            else -> {
                // Generic DuckDuckGo search
                val query = lower
                    .replace(Regex("(?i)(open browser|open link|navigate to|take me to|search for|look up)"), "")
                    .trim()
                val enc = Uri.encode(query.ifBlank { lower })
                BrowserAction("Search: $query",
                    Uri.parse("https://duckduckgo.com/?q=$enc"))
            }
        }
    }

    private fun extractQuery(lower: String, vararg remove: String): String {
        var result = lower
        remove.forEach { result = result.replace(it, "") }
        return result
            .replace(Regex("(?i)(open|search on|find on|look up on|play on|show me on|navigate to|take me to)"), "")
            .replace(Regex("[^a-zA-Z0-9 _\\-]"), " ")
            .trim()
    }

    private fun extractRawUrl(lower: String): String {
        val match = Regex("https?://[^\\s]+").find(lower)
        return match?.value ?: "https://duckduckgo.com"
    }

    private val LOCAL_BROWSER_SIGNALS = listOf(
        "open browser", "open in browser", "open link", "launch browser",
        "navigate to", "take me to", "show me on maps", "search on youtube",
        "find on youtube", "look up on google", "open google maps",
        "open wikipedia", "open github", "play on youtube", "open reddit"
    )
}
