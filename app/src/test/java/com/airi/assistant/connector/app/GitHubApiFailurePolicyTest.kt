package com.airi.assistant.connector.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubApiFailurePolicyTest {
    @Test
    fun unauthorizedCredentialIsReportedAsAuthorizationRequiredAndNotRetried() {
        val result = GitHubApiFailurePolicy.map(401)
        assertEquals("authorization_required", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun forbiddenActionWithoutRateLimitIsReportedAsMissingPermission() {
        val result = GitHubApiFailurePolicy.map(403, rateLimitRemaining = 12)
        assertEquals("authorization_required", result.code)
        assertFalse(result.retryable)
    }

    @Test
    fun providerRateLimitIsRetryableButDoesNotExposeResponseBody() {
        val result = GitHubApiFailurePolicy.map(403, rateLimitRemaining = 0)
        assertEquals("rate_limited", result.code)
        assertTrue(result.retryable)
        assertFalse(result.message.contains("token=", ignoreCase = true))
    }

    @Test
    fun hiddenOrMissingRepositoryIsNotReportedAsSuccessfulConnection() {
        val result = GitHubApiFailurePolicy.map(404)
        assertEquals("not_found", result.code)
        assertFalse(result.retryable)
        assertTrue(result.message.contains("not visible"))
    }
}
