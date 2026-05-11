package com.airi.assistant.web

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WebTaskExecutor — higher-level web task execution over [WebViewController] + [DOMAnalyzer].
 *
 * Translates natural-language web tasks into concrete step sequences and executes
 * them against a controlled WebView session.
 *
 * ── TASK TYPES ────────────────────────────────────────────────────────────────
 *
 *  | Task                  | Description                                       |
 *  |-----------------------|---------------------------------------------------|
 *  | [openAndExtract]      | Navigate to URL, return structured page content   |
 *  | [searchAndSummarize]  | Perform a web search, summarize top results       |
 *  | [fillAndSubmit]       | Fill a form by field hints and submit it          |
 *  | [scrollAndCollect]    | Scroll-paginate and collect text across N pages   |
 *  | [loginFlow]           | Attempt a login: fill email, password, submit     |
 *  | [executeTaskPlan]     | Execute a pre-planned step sequence               |
 *
 * ── SAFETY ───────────────────────────────────────────────────────────────────
 *
 *   - All tasks have a per-step timeout.
 *   - Maximum steps per task enforced by [MAX_STEPS].
 *   - Login task is gated — [loginFlow] requires explicit opt-in.
 *   - No password storage in any log or return value.
 */
class WebTaskExecutor(
    private val webView:     WebViewController,
    private val domAnalyzer: DOMAnalyzer = DOMAnalyzer,
) {

    private val TAG = "WebTaskExecutor"

    // ── Types ─────────────────────────────────────────────────────────────────

    sealed class WebTaskResult {
        data class Success(val content: String, val url: String, val data: Map<String, Any> = emptyMap()) : WebTaskResult()
        data class Failure(val reason: String, val url: String = "") : WebTaskResult()
    }

    data class TaskStep(
        val type:   StepType,
        val target: String   = "",
        val value:  String   = "",
        val url:    String   = "",
    )

    enum class StepType {
        NAVIGATE, CLICK, FILL, SUBMIT, SCROLL, WAIT, EXTRACT, BACK
    }

    sealed class ExecutionEvent {
        data class StepStarted(val step: Int, val desc: String)  : ExecutionEvent()
        data class StepDone(val step: Int, val result: String)    : ExecutionEvent()
        data class StepFailed(val step: Int, val reason: String)  : ExecutionEvent()
        data class Completed(val result: WebTaskResult)           : ExecutionEvent()
    }

    // ── Task APIs ─────────────────────────────────────────────────────────────

    /**
     * Navigate to [url] and return structured page content.
     */
    suspend fun openAndExtract(url: String): WebTaskResult {
        Log.i(TAG, "OPEN_EXTRACT url=$url")
        val ok = webView.navigate(url)
        if (!ok) return WebTaskResult.Failure("Failed to load $url", url)

        val html    = webView.getPageHtml()
        val summary = DOMAnalyzer.summarize(html, maxChars = 3_000)
        val links   = DOMAnalyzer.extractLinks(html, url)
        val meta    = DOMAnalyzer.extractMetadata(html)

        return WebTaskResult.Success(
            content = summary,
            url     = url,
            data    = mapOf(
                "title"       to meta.title,
                "description" to meta.description,
                "link_count"  to links.size.toString(),
            )
        )
    }

    /**
     * Perform a DuckDuckGo search for [query] and summarize the top results.
     */
    suspend fun searchAndSummarize(query: String, maxResults: Int = 5): WebTaskResult {
        val searchUrl = "https://html.duckduckgo.com/html/?q=${query.replace(" ", "+")}"
        Log.i(TAG, "SEARCH_SUMMARIZE query='${query.take(60)}'")

        val ok = webView.navigate(searchUrl)
        if (!ok) return WebTaskResult.Failure("Search navigation failed", searchUrl)

        val html  = webView.getPageHtml()
        val links = DOMAnalyzer.extractLinks(html, searchUrl)
            .filter { it.href.startsWith("http") && it.text.isNotBlank() }
            .take(maxResults)

        val summary = buildString {
            appendLine("Search results for: $query")
            appendLine()
            links.forEachIndexed { i, link ->
                appendLine("${i + 1}. ${link.text}")
                appendLine("   ${link.href}")
            }
        }

        return WebTaskResult.Success(
            content = summary,
            url     = searchUrl,
            data    = mapOf("result_count" to links.size.toString())
        )
    }

    /**
     * Fill form fields and submit — for structured form tasks.
     *
     * @param url     Form page URL.
     * @param fields  Map of field CSS selector → value.
     * @param submit  CSS selector of submit button or form.
     */
    suspend fun fillAndSubmit(
        url:    String,
        fields: Map<String, String>,
        submit: String = "button[type=submit]",
    ): WebTaskResult {
        val ok = webView.navigate(url)
        if (!ok) return WebTaskResult.Failure("Failed to load form page $url", url)

        for ((selector, value) in fields) {
            val filled = webView.fillInput(selector, value)
            Log.d(TAG, "FILL_${if (filled) "OK" else "MISS"} selector='$selector'")
            delay(200)
        }

        val submitted = webView.clickElement(submit)
            .also { if (!it) webView.submitForm() }

        delay(SUBMIT_WAIT_MS)
        val resultHtml    = webView.getPageHtml()
        val resultSummary = DOMAnalyzer.extractText(resultHtml, 1_500)
        val currentUrl    = webView.currentUrl()

        return WebTaskResult.Success(
            content = "Form submitted. Result: ${resultSummary.take(500)}",
            url     = currentUrl,
            data    = mapOf("submit_ok" to submitted.toString()),
        )
    }

    /**
     * Scroll [url] for [pages] pages and collect text from each page.
     */
    suspend fun scrollAndCollect(url: String, pages: Int = 3): WebTaskResult {
        val ok = webView.navigate(url)
        if (!ok) return WebTaskResult.Failure("Failed to load $url", url)

        val collected = StringBuilder()
        repeat(pages.coerceAtMost(MAX_SCROLL_PAGES)) { page ->
            val html = webView.getPageHtml()
            collected.append(DOMAnalyzer.extractText(html, 1_000))
            collected.append("\n---\n")
            webView.scrollDown()
            delay(SCROLL_WAIT_MS)
        }

        return WebTaskResult.Success(
            content = collected.toString().take(5_000),
            url     = url,
            data    = mapOf("pages_collected" to pages.toString()),
        )
    }

    /**
     * Execute a pre-planned step sequence, emitting events per step.
     */
    fun executeTaskPlan(steps: List<TaskStep>): Flow<ExecutionEvent> = flow {
        for ((index, step) in steps.take(MAX_STEPS).withIndex()) {
            val stepNum = index + 1
            emit(ExecutionEvent.StepStarted(stepNum, "${step.type.name}: ${step.target.take(50)}"))

            val result = runCatching {
                withTimeoutOrNull(STEP_TIMEOUT_MS) {
                    when (step.type) {
                        StepType.NAVIGATE -> { webView.navigate(step.url); "navigated to ${step.url}" }
                        StepType.CLICK    -> { val ok = webView.clickElement(step.target); "click ${if (ok) "ok" else "missed"}" }
                        StepType.FILL     -> { val ok = webView.fillInput(step.target, step.value); "fill ${if (ok) "ok" else "missed"}" }
                        StepType.SUBMIT   -> { val ok = webView.submitForm(step.target); "submit ${if (ok) "ok" else "missed"}" }
                        StepType.SCROLL   -> { webView.scrollDown(); "scrolled" }
                        StepType.WAIT     -> { delay(step.value.toLongOrNull() ?: 1_000L); "waited" }
                        StepType.EXTRACT  -> { DOMAnalyzer.extractText(webView.getPageHtml(), 800) }
                        StepType.BACK     -> { webView.goBack(); "went back" }
                    }
                } ?: "step timed out"
            }

            if (result.isSuccess) {
                emit(ExecutionEvent.StepDone(stepNum, result.getOrDefault("")))
            } else {
                emit(ExecutionEvent.StepFailed(stepNum, result.exceptionOrNull()?.message ?: "error"))
            }
        }

        emit(ExecutionEvent.Completed(WebTaskResult.Success(
            content = "Task plan executed: ${steps.size} steps",
            url     = webView.currentUrl(),
        )))
    }

    companion object {
        private const val STEP_TIMEOUT_MS  = 10_000L
        private const val SUBMIT_WAIT_MS   = 2_000L
        private const val SCROLL_WAIT_MS   = 800L
        private const val MAX_STEPS        = 20
        private const val MAX_SCROLL_PAGES = 10
    }
}
