package com.airi.core.chain

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val delayBetweenAttempts: Long = 500
)
