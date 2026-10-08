package com.airi.assistant.domain.execution

import java.util.UUID

@JvmInline
value class RequestId(val value: String) {
    init { require(value.isNotBlank()) }
    companion object { fun new(): RequestId = RequestId("req-${UUID.randomUUID()}") }
}

data class ExecutionRequestContract(
    val requestId: RequestId,
    val generationId: Long,
    val sessionId: String,
    val input: String,
) {
    init {
        require(generationId >= 0L)
        require(sessionId.isNotBlank())
        require(input.isNotBlank())
    }
}

sealed interface ExecutionOutcome {
    val requestId: RequestId
    val generationId: Long

    data class Success(
        override val requestId: RequestId,
        override val generationId: Long,
        val text: String,
    ) : ExecutionOutcome { init { require(text.isNotBlank()) } }
    data class Empty(
        override val requestId: RequestId,
        override val generationId: Long,
    ) : ExecutionOutcome
    data class Failure(
        override val requestId: RequestId,
        override val generationId: Long,
        val reason: String,
    ) : ExecutionOutcome
    data class Timeout(
        override val requestId: RequestId,
        override val generationId: Long,
    ) : ExecutionOutcome
    data class Cancelled(
        override val requestId: RequestId,
        override val generationId: Long,
    ) : ExecutionOutcome
    data class Partial(
        override val requestId: RequestId,
        override val generationId: Long,
        val text: String,
        val terminalCause: TerminalCause,
    ) : ExecutionOutcome { init { require(text.isNotBlank()) } }

    enum class TerminalCause { FAILURE, TIMEOUT }
}

/** Exactly one terminal outcome may be published for one request. */
class TerminalOutcomeGuard {
    private var published = false
    fun tryPublish(): Boolean = synchronized(this) {
        if (published) false else { published = true; true }
    }
    fun isPublished(): Boolean = synchronized(this) { published }
}
