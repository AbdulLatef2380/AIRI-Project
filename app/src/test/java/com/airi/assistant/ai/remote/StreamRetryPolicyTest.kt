package com.airi.assistant.ai.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamRetryPolicyTest {
    @Test fun retriesNetworkFailureBeforeFirstToken() {
        assertTrue(RemoteModelExecutor.shouldRetryStream("Connection reset", tokensDelivered = false))
    }

    @Test fun neverRetriesAfterATokenWasDelivered() {
        assertFalse(RemoteModelExecutor.shouldRetryStream("Connection reset", tokensDelivered = true))
    }

    @Test fun retriesRateLimitAndServerErrorsOnly() {
        assertTrue(RemoteModelExecutor.shouldRetryStream("HTTP 429: slow down", false))
        assertTrue(RemoteModelExecutor.shouldRetryStream("HTTP 503: unavailable", false))
        assertFalse(RemoteModelExecutor.shouldRetryStream("HTTP 401: bad key", false))
        assertFalse(RemoteModelExecutor.shouldRetryStream("HTTP 400: bad request", false))
    }

    @Test fun doesNotRetryTimeoutOrPolicyRejection() {
        assertFalse(RemoteModelExecutor.shouldRetryStream("Remote model timed out after 90s", false))
        assertFalse(RemoteModelExecutor.shouldRetryStream("Endpoint rejected by local security policy", false))
    }
}
