package com.airi.assistant.voice

/** Shared sensitivity contract for Porcupine and OpenWakeWord. */
object WakeWordSensitivityPolicy {
    const val DEFAULT = 0.60f
    const val MIN = 0.10f
    const val MAX = 0.95f

    fun clamp(value: Float): Float = value.coerceIn(MIN, MAX)

    /** Higher user sensitivity lowers the score required by OpenWakeWord. */
    fun openWakeWordThreshold(sensitivity: Float): Float {
        val normalized = (clamp(sensitivity) - MIN) / (MAX - MIN)
        return (0.82f - normalized * 0.42f).coerceIn(0.40f, 0.82f)
    }
}
