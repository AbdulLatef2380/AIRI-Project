package com.airi.assistant.runtime.recovery

/**
 * Pure decision table for Android trim-memory callbacks.
 *
 * Keeping this separate from Application makes the dangerous boundary easy to
 * regression-test without an Android device. UI_HIDDEN is deliberately a
 * background signal; it must not unload a live native model by itself.
 */
object MemoryPressurePolicy {
    enum class Severity { LOW, CRITICAL, COMPLETE, BACKGROUND }

    data class Decision(
        val severity: Severity,
        val shouldReleaseNativeMemory: Boolean,
        val shouldPruneStaleWorkspaces: Boolean,
    )

    // Android ComponentCallbacks2 values. Kept numeric here so this decision
    // table remains JVM-testable and does not pull Android stubs into tests.
    private const val TRIM_MEMORY_RUNNING_LOW = 10
    private const val TRIM_MEMORY_RUNNING_CRITICAL = 15
    private const val TRIM_MEMORY_UI_HIDDEN = 20
    private const val TRIM_MEMORY_COMPLETE = 80

    fun classify(level: Int): Decision? = when {
        level >= TRIM_MEMORY_COMPLETE -> Decision(
            severity = Severity.COMPLETE,
            shouldReleaseNativeMemory = true,
            shouldPruneStaleWorkspaces = true,
        )
        level >= TRIM_MEMORY_UI_HIDDEN -> Decision(
            severity = Severity.BACKGROUND,
            shouldReleaseNativeMemory = false,
            shouldPruneStaleWorkspaces = true,
        )
        level >= TRIM_MEMORY_RUNNING_CRITICAL -> Decision(
            severity = Severity.CRITICAL,
            shouldReleaseNativeMemory = true,
            shouldPruneStaleWorkspaces = true,
        )
        level >= TRIM_MEMORY_RUNNING_LOW -> Decision(
            severity = Severity.LOW,
            shouldReleaseNativeMemory = false,
            shouldPruneStaleWorkspaces = false,
        )
        else -> null
    }
}
