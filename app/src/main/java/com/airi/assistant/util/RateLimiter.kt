package com.airi.assistant.util

class RateLimiter(
    private val maxRequests: Int,
    private val windowMs: Long
) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = System.currentTimeMillis()
        while (timestamps.isNotEmpty() && now - timestamps.first() > windowMs) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxRequests) return false
        timestamps.addLast(now)
        return true
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}
