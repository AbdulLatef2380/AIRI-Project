package com.airi.assistant.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSyncRetryPolicyTest {
    @Test
    fun retryBudgetAllowsInitialAttemptAndThreeRetriesOnly() {
        assertTrue(CloudSyncRetryPolicy.shouldRetry(0))
        assertTrue(CloudSyncRetryPolicy.shouldRetry(1))
        assertTrue(CloudSyncRetryPolicy.shouldRetry(2))
        assertFalse(CloudSyncRetryPolicy.shouldRetry(3))
        assertFalse(CloudSyncRetryPolicy.shouldRetry(4))
    }
}
