package com.airi.assistant.voice

/** Process-wide exclusive lease for microphone capture engines. */
object VoiceCaptureArbiter {
    private val lock = Any()
    @Volatile private var activeOwner: String? = null
    private val preemptionHandlers = mutableMapOf<String, () -> Unit>()

    val currentOwner: String? get() = activeOwner

    fun registerPreemptionHandler(owner: String, handler: () -> Unit) = synchronized(lock) {
        require(owner.isNotBlank())
        preemptionHandlers[owner] = handler
    }

    fun unregisterPreemptionHandler(owner: String) = synchronized(lock) {
        preemptionHandlers.remove(owner)
    }

    /** A different owner can be displaced only when the caller explicitly requests handoff. */
    fun acquire(owner: String, preemptExisting: Boolean = false): Boolean = synchronized(lock) {
        require(owner.isNotBlank())
        val current = activeOwner
        if (current == null || current == owner) {
            activeOwner = owner
            return@synchronized true
        }
        if (!preemptExisting) return@synchronized false
        val handler = preemptionHandlers[current] ?: return@synchronized false
        try {
            handler()
        } catch (_: Exception) {
            return@synchronized false
        }
        if (activeOwner != null && activeOwner != current) return@synchronized false
        // If the handler did not explicitly release, do not grant an overlapping capture.
        if (activeOwner == current) return@synchronized false
        activeOwner = owner
        true
    }

    fun release(owner: String): Boolean = synchronized(lock) {
        if (activeOwner != owner) return@synchronized false
        activeOwner = null
        true
    }
}
