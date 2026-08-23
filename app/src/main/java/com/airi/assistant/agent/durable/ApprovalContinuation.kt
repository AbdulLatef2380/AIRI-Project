package com.airi.assistant.agent.durable

import java.util.UUID

/**
 * Durable description of one side effect that stopped before it was invoked.
 *
 * A continuation is deliberately narrower than a task checkpoint: it binds one
 * approval to one task/run/step and contains only a bounded, non-secret
 * connector request. Credentials, binary payloads, and opaque callbacks are
 * never persisted here. This lets a newly-created process resume the exact
 * approved operation without re-planning the enclosing task or replaying an
 * already-executed side effect.
 */
data class ApprovalContinuation(
    val id: String = UUID.randomUUID().toString(),
    val approvalId: String,
    val taskId: String = "",
    val missionId: String = "",
    val projectId: String? = null,
    val runId: String,
    val stepId: String,
    /** Present only for a connector side effect. */
    val invocation: ResumableConnectorInvocation? = null,
    /** Present only for a local managed-project text revision. */
    val projectFileWrite: ResumableProjectFileWrite? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val expiresAtMs: Long,
    val status: ApprovalContinuationStatus = ApprovalContinuationStatus.PENDING,
    val claimedAtMs: Long = -1L,
    val finishedAtMs: Long = -1L,
    val outcome: String = ""
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = expiresAtMs <= nowMs

    /** Exactly one typed invocation is required; hybrid descriptors fail closed. */
    fun isSafeToPersist(): Boolean = when {
        invocation != null && projectFileWrite == null -> invocation.isSafeToPersist()
        invocation == null && projectFileWrite != null -> projectFileWrite.isSafeToPersist()
        else -> false
    }

    /**
     * A continuation is executable only once. `CLAIMED` is persisted before any
     * connector call, so a second UI click, retry loop, or stale callback cannot
     * invoke the same external side effect a second time.
     */
    fun claim(nowMs: Long = System.currentTimeMillis()): ApprovalContinuation? = when {
        status != ApprovalContinuationStatus.PENDING -> null
        isExpired(nowMs) -> null
        else -> copy(status = ApprovalContinuationStatus.CLAIMED, claimedAtMs = nowMs)
    }

    fun complete(outcome: String, nowMs: Long = System.currentTimeMillis()): ApprovalContinuation? =
        if (status == ApprovalContinuationStatus.CLAIMED) {
            copy(
                status = ApprovalContinuationStatus.COMPLETED,
                finishedAtMs = nowMs,
                outcome = safeOutcome(outcome)
            )
        } else {
            null
        }

    fun fail(reason: String, nowMs: Long = System.currentTimeMillis()): ApprovalContinuation? =
        if (status == ApprovalContinuationStatus.CLAIMED) {
            copy(
                status = ApprovalContinuationStatus.FAILED,
                finishedAtMs = nowMs,
                outcome = safeOutcome(reason)
            )
        } else {
            null
        }

    fun reject(reason: String, nowMs: Long = System.currentTimeMillis()): ApprovalContinuation =
        if (status == ApprovalContinuationStatus.PENDING) {
            copy(
                status = if (isExpired(nowMs)) ApprovalContinuationStatus.EXPIRED else ApprovalContinuationStatus.REJECTED,
                finishedAtMs = nowMs,
                outcome = safeOutcome(reason)
            )
        } else {
            this
        }

    private fun safeOutcome(value: String): String =
        value.replace(Regex("\\s+"), " ").trim().take(MAX_OUTCOME_CHARS)

    private companion object {
        const val MAX_OUTCOME_CHARS = 480
    }
}

enum class ApprovalContinuationStatus {
    PENDING,
    CLAIMED,
    COMPLETED,
    FAILED,
    REJECTED,
    EXPIRED
}

/**
 * Replayable connector input kept only while awaiting one explicit approval.
 *
 * It intentionally excludes `binary`, credentials, authorization headers, and
 * arbitrary object callbacks. The caller must reject any user payload that
 * resembles a credential instead of silently storing it in durable JSON.
 */
/**
 * ID-and-hash-only description of one approved write to a managed project text
 * file. Candidate bytes, source paths, backups, credentials, and user text stay
 * in private editor storage and are never placed in a durable continuation.
 */
data class ResumableProjectFileWrite(
    val proposalId: String,
    val targetFileId: String,
    val baseContentHash: String,
    val candidateContentHash: String,
    val idempotencyKey: String
) {
    fun isSafeToPersist(): Boolean =
        proposalId.matches(SAFE_ID) &&
            targetFileId.matches(SAFE_ID) &&
            idempotencyKey.matches(SAFE_ID) &&
            baseContentHash.matches(SHA256) &&
            candidateContentHash.matches(SHA256)

    private companion object {
        val SAFE_ID = Regex("^[A-Za-z0-9._-]{1,128}$")
        val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}

/**
 * Replayable connector input kept only while awaiting one explicit approval.
 *
 * It intentionally excludes `binary`, credentials, authorization headers, and
 * arbitrary object callbacks. The caller must reject any user payload that
 * resembles a credential instead of silently storing it in durable JSON.
 */
data class ResumableConnectorInvocation(
    val connectorId: String,
    val action: String,
    val text: String = "",
    val params: Map<String, String> = emptyMap(),
    val idempotencyKey: String
) {
    fun isSafeToPersist(): Boolean {
        if (connectorId.isBlank() || action.isBlank() || idempotencyKey.isBlank()) return false
        if (text.length > MAX_TEXT_CHARS || params.size > MAX_PARAM_COUNT) return false
        return !looksSensitive(text) && params.all { (key, value) ->
            key.length <= MAX_PARAM_KEY_CHARS &&
                value.length <= MAX_PARAM_VALUE_CHARS &&
                !looksSensitive(key) &&
                !looksSensitive(value)
        }
    }

    private fun looksSensitive(value: String): Boolean = SENSITIVE_VALUE.containsMatchIn(value)

    private companion object {
        const val MAX_TEXT_CHARS = 8_192
        const val MAX_PARAM_COUNT = 32
        const val MAX_PARAM_KEY_CHARS = 80
        const val MAX_PARAM_VALUE_CHARS = 4_096
        val SENSITIVE_VALUE = Regex(
            "(?i)(api[_ -]?key|authorization|bearer\\s+[a-z0-9._-]{8,}|password|secret|token\\s*[:=])"
        )
    }
}
