package com.airi.assistant.web

import android.content.Context
import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * WebAgentRuntime — unified web automation facade implementing [SubAgent].
 */
class WebAgentRuntime(private val context: Context) : SubAgent {

    private val TAG = "WebAgentRuntime"

    override val capability = SubAgentCapability(
        agentId             = "web_agent_runtime",
        displayName         = "Web Agent",
        description         = "Autonomous web browsing, form filling, and page content extraction.",
        intentKeywords      = listOf(
            "open website", "go to", "visit", "browse", "navigate to",
            "search online", "find on the web", "look up online",
            "fill form", "submit form", "extract from page",
            "scroll and collect", "scrape", "read the page",
            "what does the site say", "open the link",
        ),
        domains             = listOf("web", "browser", "url", "http", "form", "search", "scrape"),
        requiresCloud       = false,
        requiredTools       = listOf("web_view"),
        costTier            = SubAgentCapability.CostTier.STANDARD,
        latencyProfile      = SubAgentCapability.LatencyProfile.SLOW,
        supportsBackground  = false,
        maxParallelSubTasks = 1,
        supportsResume      = false,
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        if (context.privacyLevel == 0) return false
        val lower = input.lowercase()
        return URL_REGEX.containsMatchIn(input) ||
               INTENT_SIGNALS.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val startTime = System.currentTimeMillis()
        if (context.privacyLevel == 0) {
            emit(AgentEvent.Failed("Web agent is disabled in PRIVACY_MAXIMUM mode."))
            return@flow
        }

        emit(AgentEvent.Progress("Initializing web session…", 5, "web_init"))

        val controller = WebViewController(this@WebAgentRuntime.context)
        val executor   = WebTaskExecutor(controller)

        try {
            controller.create()
            emit(AgentEvent.Progress("Web session ready", 10, "web_ready"))

            val taskType = classifyIntent(input)
            Log.i(TAG, "WEB_TASK type=$taskType input='${input.take(80)}'")

            val result = when (taskType) {
                TaskType.OPEN_EXTRACT -> {
                    val url = extractUrl(input) ?: "https://www.google.com/search?q=${input.replace(" ", "+")}"
                    emit(AgentEvent.Progress("Loading $url…", 20, "web_navigate"))
                    executor.openAndExtract(url)
                }
                TaskType.SEARCH -> {
                    val query = extractQuery(input)
                    emit(AgentEvent.Progress("Searching: $query…", 20, "web_search"))
                    executor.searchAndSummarize(query)
                }
                TaskType.SCROLL_COLLECT -> {
                    val url = extractUrl(input) ?: return@flow
                    emit(AgentEvent.Progress("Loading and collecting…", 20, "web_scroll"))
                    executor.scrollAndCollect(url, pages = 3)
                }
                TaskType.GENERIC -> {
                    val url = extractUrl(input)
                    if (url != null) {
                        emit(AgentEvent.Progress("Loading $url…", 20, "web_generic_open"))
                        executor.openAndExtract(url)
                    } else {
                        emit(AgentEvent.Progress("Searching…", 20, "web_generic_search"))
                        executor.searchAndSummarize(input)
                    }
                }
            }

            when (result) {
                is WebTaskExecutor.WebTaskResult.Success -> {
                    emit(AgentEvent.Progress("Analyzing results…", 85, "web_analyze"))
                    val response = buildString {
                        appendLine(result.content)
                        if (result.url.isNotBlank()) appendLine("\nSource: ${result.url}")
                    }
                    emit(AgentEvent.Complete(
                        result = response.trim(),
                        durationMs = System.currentTimeMillis() - startTime,
                        toolsUsed = listOf("web_browser")
                    ))
                }
                is WebTaskExecutor.WebTaskResult.Failure -> {
                    emit(AgentEvent.Failed(reason = "Web task failed: ${result.reason}"))
                }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "WEB_AGENT_ERROR: ${e.message}", e)
            emit(AgentEvent.Failed(reason = "Web agent error: ${e.message}"))
        } finally {
            controller.destroy()
        }
    }

    // ── Intent classification ─────────────────────────────────────────────────

    private enum class TaskType { OPEN_EXTRACT, SEARCH, SCROLL_COLLECT, GENERIC }

    private fun classifyIntent(input: String): TaskType {
        val lower = input.lowercase()
        return when {
            lower.contains("search") || lower.contains("find online") ||
            lower.contains("look up") -> TaskType.SEARCH
            lower.contains("scroll") && lower.contains("collect") -> TaskType.SCROLL_COLLECT
            URL_REGEX.containsMatchIn(input) -> TaskType.OPEN_EXTRACT
            else -> TaskType.GENERIC
        }
    }

    private fun extractUrl(input: String): String? =
        URL_REGEX.find(input)?.value?.let { url ->
            if (url.startsWith("http")) url else "https://$url"
        }

    private fun extractQuery(input: String): String {
        val lower = input.lowercase()
        val after = listOf("search for", "search ", "find online", "look up", "look up online")
            .mapNotNull { prefix ->
                val idx = lower.indexOf(prefix)
                if (idx >= 0) input.substring(idx + prefix.length).trim() else null
            }
            .firstOrNull { it.isNotBlank() }
        return after ?: input.take(100)
    }

    companion object {
        private val URL_REGEX = Regex("""https?://[^\s]+|www\.[^\s]+\.[^\s]{2,}""")
        private val INTENT_SIGNALS = listOf(
            "open website", "go to", "visit ", "browse", "navigate to",
            "search online", "find on the web", "look up online",
            "what does the site", "read the page", "open the link",
        )
    }
}
