package com.airi.assistant.agent.sandbox

/** Evidence returned by the isolated process probe. No field is inferred by the caller. */
data class OsSandboxCapability(
    val isolatedProcess: Boolean,
    val uidSeparated: Boolean,
    val appFilesDenied: Boolean,
    val networkDenied: Boolean,
    val limitsEnforced: Boolean,
    val reason: String,
) {
    val isReleaseReady: Boolean
        get() = isolatedProcess && uidSeparated && appFilesDenied && networkDenied && limitsEnforced

    companion object {
        fun unavailable(reason: String) = OsSandboxCapability(
            isolatedProcess = false,
            uidSeparated = false,
            appFilesDenied = false,
            networkDenied = false,
            limitsEnforced = false,
            reason = reason,
        )
    }
}
