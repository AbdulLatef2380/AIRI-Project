package com.airi.assistant.auth.identity

/**
 * Platform-independent biometric authentication session policy.
 *
 * The Android prompt is only an interaction mechanism. This service owns the
 * capability/session contract so a prompt callback cannot authorize work after
 * the host moved to the background, rotated, or recreated its process.
 */
class BiometricAuthService {
    enum class Outcome { SUCCESS, CANCELLED, FAILURE }
    enum class Result { SUCCESS, CANCELLED, FAILURE, UNAVAILABLE, STALE }

    class Session internal constructor(internal val id: Long)

    private var nextId = 0L
    private var generation = 0L
    private var foreground = true
    private var active: ActiveSession? = null

    private data class ActiveSession(val session: Session, val generation: Long)

    /** Starts a foreground authentication session, or returns null if unavailable. */
    @Synchronized
    fun begin(available: Boolean): Session? {
        if (!available || !foreground) return null
        val session = Session(++nextId)
        active = ActiveSession(session, generation)
        return session
    }

    /** Completes only the currently active session from the same lifecycle generation. */
    @Synchronized
    fun complete(session: Session, outcome: Outcome): Result {
        val current = active
        if (current == null || current.session.id != session.id || current.generation != generation || !foreground) {
            return Result.STALE
        }
        active = null
        return when (outcome) {
            Outcome.SUCCESS -> Result.SUCCESS
            Outcome.CANCELLED -> Result.CANCELLED
            Outcome.FAILURE -> Result.FAILURE
        }
    }

    @Synchronized
    fun onBackground() {
        foreground = false
        invalidate()
    }

    @Synchronized
    fun onForeground() {
        foreground = true
        invalidate()
    }

    @Synchronized
    fun onRotation() {
        invalidate()
    }

    @Synchronized
    fun onProcessRecreated() {
        foreground = true
        invalidate()
    }

    @Synchronized
    fun hasActiveSession(): Boolean = active != null

    private fun invalidate() {
        generation++
        active = null
    }
}
