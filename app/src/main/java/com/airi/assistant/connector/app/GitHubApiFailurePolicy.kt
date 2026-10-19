package com.airi.assistant.connector.app

import com.airi.assistant.connector.ConnectorOutput

/** Safe, status-aware mapping for GitHub REST responses; response bodies are never echoed. */
internal object GitHubApiFailurePolicy {
    fun map(
        statusCode: Int,
        rateLimitRemaining: Long? = null,
        action: String? = null,
    ): ConnectorOutput.Failure = when {
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
            if (action == "push") "provider_permission_denied" else "authorization_required",
            if (action == "push") {
                "GitHub is connected, but this token does not have Contents: write for the target repository. Re-authorize with write access."
            } else {
                "The GitHub token lacks permission for this repository action. Check token and repository access."
            },
            retryable = false,
        )
        statusCode == 404 -> ConnectorOutput.Failure(
            "not_found",
            "The GitHub resource was not found or is not visible to this token.",
            retryable = false,
        )
        statusCode == 409 -> ConnectorOutput.Failure(
            "conflict",
            "GitHub rejected the update because the branch or blob SHA is stale. Read the file again and retry with its current SHA.",
            retryable = false,
        )
        statusCode == 422 -> ConnectorOutput.Failure(
            "invalid_argument",
            "GitHub rejected the request parameters or content. Verify the repository, path, branch, commit message, and SHA.",
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
