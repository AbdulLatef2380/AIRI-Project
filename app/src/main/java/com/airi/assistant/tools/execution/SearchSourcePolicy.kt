package com.airi.assistant.tools.execution

import com.airi.assistant.agent.browser.BrowserNavigationPolicy

/** Shared boundary for search-result fetches and external browser hand-offs. */
object SearchSourcePolicy {
    sealed class Decision {
        data class ReadAllowed(val normalizedUrl: String) : Decision()
        data class RequiresUserTakeover(val normalizedUrl: String, val reason: String) : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    fun evaluateRead(url: String): Decision = when (val decision =
        BrowserNavigationPolicy.evaluate(url, BrowserNavigationPolicy.Operation.READ)
    ) {
        is BrowserNavigationPolicy.Decision.Allow -> Decision.ReadAllowed(decision.normalizedUrl)
        is BrowserNavigationPolicy.Decision.Blocked -> Decision.Blocked(decision.reason)
        else -> Decision.Blocked("Search fetch is not permitted by navigation policy")
    }

    fun evaluateExternalOpen(url: String): Decision = when (val decision =
        BrowserNavigationPolicy.evaluate(url, BrowserNavigationPolicy.Operation.OPEN_EXTERNAL)
    ) {
        is BrowserNavigationPolicy.Decision.RequiresUserTakeover ->
            Decision.RequiresUserTakeover(decision.normalizedUrl, decision.reason)
        is BrowserNavigationPolicy.Decision.Blocked -> Decision.Blocked(decision.reason)
        else -> Decision.Blocked("External search hand-off is not permitted by navigation policy")
    }
}
