package com.airi.assistant.core.runtime

/**
 * PersistentTaskSession — immutable data model for a resumable agent session.
 *
 * A session captures everything needed to resume a cognitive loop after a
 * process kill, crash, or deliberate suspension:
 *
 *   - [sessionId]       : globally unique UUID
 *   - [goalText]        : the original user goal (natural language)
 *   - [agentId]         : agent responsible for execution
 *   - [status]          : lifecycle state
 *   - [checkpointJson]  : opaque JSON blob written by [TaskCheckpointStore]
 *   - [stepIndex]       : how many steps have been committed
 *   - [totalSteps]      : planner-estimated total (0 if unknown)
 *   - [createdAtMs]     : creation epoch millis
 *   - [updatedAtMs]     : last mutation epoch millis
 *   - [finishedAtMs]    : terminal epoch millis (0 if not terminal)
 *   - [resultSummary]   : human-readable outcome (set on completion/failure)
 *   - [errorMessage]    : last error if [status] == FAILED
 *   - [metadata]        : arbitrary key-value pairs for agent-specific context
 */
data class PersistentTaskSession(
    val sessionId:      String,
    val goalText:       String,
    val agentId:        String,
    val status:         SessionStatus       = SessionStatus.PENDING,
    val checkpointJson: String              = "",
    val stepIndex:      Int                 = 0,
    val totalSteps:     Int                 = 0,
    val createdAtMs:    Long                = System.currentTimeMillis(),
    val updatedAtMs:    Long                = System.currentTimeMillis(),
    val finishedAtMs:   Long                = 0L,
    val resultSummary:  String              = "",
    val errorMessage:   String              = "",
    val metadata:       Map<String, String> = emptyMap()
) {
    /** True when no further transitions are possible. */
    val isTerminal: Boolean get() = status == SessionStatus.COMPLETED
        || status == SessionStatus.FAILED
        || status == SessionStatus.CANCELLED

    /** Progress [0..100] estimated from step counts. */
    val progressPercent: Int get() = when {
        totalSteps <= 0                     -> if (isTerminal) 100 else 0
        status == SessionStatus.COMPLETED   -> 100
        isTerminal                          -> stepIndex * 100 / totalSteps
        else                                -> (stepIndex * 100 / totalSteps).coerceIn(0, 99)
    }

    fun withStatus(s: SessionStatus) = copy(status = s, updatedAtMs = System.currentTimeMillis())
    fun withCheckpoint(json: String, step: Int = stepIndex) =
        copy(checkpointJson = json, stepIndex = step, updatedAtMs = System.currentTimeMillis())
    fun withCompleted(result: String) = copy(
        status        = SessionStatus.COMPLETED,
        resultSummary = result,
        stepIndex     = totalSteps.coerceAtLeast(stepIndex),
        finishedAtMs  = System.currentTimeMillis(),
        updatedAtMs   = System.currentTimeMillis()
    )
    fun withFailed(reason: String) = copy(
        status       = SessionStatus.FAILED,
        errorMessage = reason,
        finishedAtMs = System.currentTimeMillis(),
        updatedAtMs  = System.currentTimeMillis()
    )
    fun withCancelled() = copy(
        status       = SessionStatus.CANCELLED,
        finishedAtMs = System.currentTimeMillis(),
        updatedAtMs  = System.currentTimeMillis()
    )
}

enum class SessionStatus {
    PENDING,    // created, not yet started
    RUNNING,    // actively executing
    SUSPENDED,  // paused (e.g. waiting for user input or resource)
    COMPLETED,  // successfully finished
    FAILED,     // terminated with error
    CANCELLED   // user- or system-initiated cancellation
}
