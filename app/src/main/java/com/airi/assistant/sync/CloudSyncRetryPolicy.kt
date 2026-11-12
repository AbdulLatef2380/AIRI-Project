package com.airi.assistant.sync

/** Pure retry budget shared by the worker and JVM tests. */
internal object CloudSyncRetryPolicy {
    const val MAX_RETRIES = 3

    /** WorkManager's runAttemptCount starts at zero for the first attempt. */
    fun shouldRetry(runAttemptCount: Int): Boolean = runAttemptCount < MAX_RETRIES
}
