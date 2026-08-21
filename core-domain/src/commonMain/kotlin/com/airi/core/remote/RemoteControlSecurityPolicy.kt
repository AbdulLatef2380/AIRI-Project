package com.airi.core.remote

data class RemoteRateLimit(
    val maxEvents: Int,
    val windowMillis: Long
) {
    init {
        require(maxEvents > 0) { "A rate limit must allow at least one event." }
        require(windowMillis > 0) { "A rate limit window must be positive." }
    }
}

data class RemoteAuditEvent(
    val eventId: String,
    val ownerId: String,
    val deviceId: String,
    val eventType: String,
    val commandType: RemoteControlCommandType?,
    val outcome: Outcome,
    val occurredAtMillis: Long
) {
    enum class Outcome {
        ACCEPTED,
        REJECTED,
        REVOKED
    }
}

sealed interface RemoteRateLimitDecision {
    data object Allowed : RemoteRateLimitDecision
    data class Rejected(val retryAfterMillis: Long) : RemoteRateLimitDecision
}

object RemoteControlSecurityPolicy {
    val commandRateLimit = RemoteRateLimit(maxEvents = 30, windowMillis = 60_000L)
    val pairingAttemptRateLimit = RemoteRateLimit(maxEvents = DevicePairingPolicy.MAX_PAIRING_ATTEMPTS, windowMillis = DevicePairingPolicy.MAX_PAIRING_WINDOW_MILLIS)

    fun decideRateLimit(
        previousEventMillis: List<Long>,
        limit: RemoteRateLimit,
        nowMillis: Long
    ): RemoteRateLimitDecision {
        val windowStart = nowMillis - limit.windowMillis
        val eventsInWindow = previousEventMillis.filter { it > windowStart && it <= nowMillis }
        if (eventsInWindow.size < limit.maxEvents) return RemoteRateLimitDecision.Allowed
        val oldest = eventsInWindow.minOrNull() ?: return RemoteRateLimitDecision.Allowed
        return RemoteRateLimitDecision.Rejected((oldest + limit.windowMillis - nowMillis).coerceAtLeast(0L))
    }

    fun audit(
        eventId: String,
        ownerId: String,
        deviceId: String,
        commandType: RemoteControlCommandType?,
        outcome: RemoteAuditEvent.Outcome,
        nowMillis: Long
    ): RemoteAuditEvent = RemoteAuditEvent(
        eventId = eventId,
        ownerId = ownerId,
        deviceId = deviceId,
        eventType = if (commandType == null) "pairing" else "command",
        commandType = commandType,
        outcome = outcome,
        occurredAtMillis = nowMillis
    )
}
