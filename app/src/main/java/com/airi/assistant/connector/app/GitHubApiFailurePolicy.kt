package com.airi.assistant.connector.app

import com.airi.assistant.connector.ConnectorOutput

/** Safe, status-aware mapping for GitHub REST responses; response bodies are never echoed. */
internal object GitHubApiFailurePolicy {
    fun map(statusCode: Int, rateLimitRemaining: Long? = null): ConnectorOutput.Failure = when {
        statusCode == 401 -> ConnectorOutput.Failure(
            "authorization_required",
            "GitHub rejected the credential. Reconnect GitHub and verify the personal access token.",
            retryable = false,
        )
        statusCode == 403 && rateLimitRemaining == 0L || statusCode == 429 -> ConnectorOutput.Failure(
            "rate_limited",
            "GitHub API rate limit reached. Retry after the provider reset window.",
            retryable = true,
        )
        statusCode == 403 -> ConnectorOutput.Failure(
            "authorization_required",
            "The GitHub token lacks permission for this repository action. Check token and repository access.",
            retryable = false,
        )
        statusCode == 404 -> ConnectorOutput.Failure(
            "not_found",
            "The GitHub resource was not found or is not visible to this token.",
            retryable = false,
        )
        statusCode >= 500 -> ConnectorOutput.Failure(
            "provider_error",
            "GitHub is temporarily unavailable.",
            retryable = true,
        )
        else -> ConnectorOutput.Failure(
            "provider_error",
            "GitHub rejected the request (HTTP $statusCode).",
            retryable = false,
        )
    }
}

internal class GitHubApiHttpException(
    val statusCode: Int,
    val rateLimitRemaining: Long?,
) : Exception("GitHub REST request failed with HTTP $statusCode")
