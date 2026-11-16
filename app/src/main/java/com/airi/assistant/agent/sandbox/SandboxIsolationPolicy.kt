package com.airi.assistant.agent.sandbox

/**
 * Release gate for the current SandboxExecutor boundary. Its controls are
 * application-level policy checks, not OS process isolation; therefore shell
 * execution is development-only until a stronger runtime is available.
 */
object SandboxIsolationPolicy {
    /**
     * Compile-time safety fuse. It stays false until the device matrix proves
     * the isolated service contract; a service declaration alone is not proof.
     */
    const val OS_SANDBOX_AVAILABLE: Boolean = false

    fun mayExecuteShell(
        isDebugBuild: Boolean,
        capability: OsSandboxCapability? = null,
    ): Boolean =
        isDebugBuild || (OS_SANDBOX_AVAILABLE && capability?.isReleaseReady == true)

    fun releaseBlockReason(capability: OsSandboxCapability? = null): String =
        capability?.reason?.takeIf { it.isNotBlank() } ?: RELEASE_BLOCK_REASON

    const val RELEASE_BLOCK_REASON =
        "Terminal is disabled in release builds until OS-level process isolation is available"
}
