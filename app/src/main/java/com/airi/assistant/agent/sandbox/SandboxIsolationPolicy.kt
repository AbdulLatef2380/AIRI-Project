package com.airi.assistant.agent.sandbox

/**
 * Release gate for the current SandboxExecutor boundary. Its controls are
 * application-level policy checks, not OS process isolation; therefore shell
 * execution is development-only until a stronger runtime is available.
 */
object SandboxIsolationPolicy {
    const val OS_SANDBOX_AVAILABLE: Boolean = false

    fun mayExecuteShell(isDebugBuild: Boolean): Boolean =
        isDebugBuild || OS_SANDBOX_AVAILABLE

    const val RELEASE_BLOCK_REASON =
        "Terminal is disabled in release builds until OS-level process isolation is available"
}
