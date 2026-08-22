package com.airi.assistant.agent.subagent.impl

import com.airi.assistant.agent.browser.BrowserNavigationPolicy

/**
 * Boundary for external research evidence before it enters a model prompt.
 *
 * Search snippets and extracted pages may contain prompt-injection text. The
 * policy keeps evidence bounded, strips control characters, and retains only a
 * public HTTP(S) source that is safe to present as a read-only citation.
 */
object ResearchEvidencePolicy {
    const val MAX_EVIDENCE_CHARS = 3_000

    data class Evidence(
        val citationId: String,
        val provider: String,
        val content: String,
        val sourceUrl: String?
    )

    fun fromSearchResult(
        provider: String,
        summary: String,
        sourceUrl: String,
        citationId: String = "source-1"
    ): Evidence? {
        val bounded = summary
            .replace(Regex("[\\u0000-\\u001F&&[^\\n\\t]]"), " ")
            .trim()
            .take(MAX_EVIDENCE_CHARS)
        if (bounded.isBlank()) return null
        val source = (BrowserNavigationPolicy.evaluate(sourceUrl, BrowserNavigationPolicy.Operation.READ)
            as? BrowserNavigationPolicy.Decision.Allow)
            ?.normalizedUrl
        return Evidence(
            citationId = citationId,
            provider = provider.take(64).ifBlank { "search" },
            content = bounded,
            sourceUrl = source
        )
    }

    fun formatForSynthesis(evidence: Evidence): String = buildString {
        appendLine("<research_evidence id=\"${evidence.citationId}\" provider=\"${evidence.provider}\" trust=\"untrusted_external\">")
        evidence.sourceUrl?.let { appendLine("Citation URL: $it") }
        appendLine("Evidence content:")
        appendLine(evidence.content)
        append("</research_evidence>")
    }
}
