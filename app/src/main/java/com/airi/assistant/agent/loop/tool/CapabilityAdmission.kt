package com.airi.assistant.agent.loop.tool

/** Machine-readable explanation for every capability admission decision. */
data class CapabilityDecision(
    val capabilityId: String,
    val requested: Boolean,
    val registered: Boolean,
    val exposed: Boolean,
    val executable: Boolean,
    val status: Availability,
    val reason: String? = null,
    val nextAction: NextAction = NextAction.NONE,
)

enum class Availability {
    READY,
    NOT_REQUESTED,
    NOT_REGISTERED,
    DISCONNECTED,
    AUTH_REQUIRED,
    PERMISSION_REQUIRED,
    CONTEXT_OMITTED,
    BLOCKED_BY_POLICY,
    CONFIGURATION_REQUIRED,
    DEPENDENCY_MISSING,
    BUILD_FAILED,
    UNSUPPORTED,
}

enum class NextAction {
    NONE,
    CONNECT,
    AUTHORIZE,
    GRANT_PERMISSION,
    SHORTEN_REQUEST,
    RETRY,
    RECONFIGURE,
}

data class CapabilitySourceFailure(
    val sourceId: String,
    val stage: String,
    val message: String,
    val causeType: String,
)

sealed class CapabilityAssemblyResult {
    data class Ready(
        val catalog: RuntimeToolCatalog.Result,
        val decisions: List<CapabilityDecision> = emptyList(),
    ) : CapabilityAssemblyResult()
    data class Degraded(
        val catalog: RuntimeToolCatalog.Result,
        val failedSources: List<CapabilitySourceFailure>,
        val decisions: List<CapabilityDecision> = emptyList(),
    ) : CapabilityAssemblyResult()
    data class Failed(
        val failures: List<CapabilitySourceFailure>,
        val decisions: List<CapabilityDecision> = emptyList(),
    ) : CapabilityAssemblyResult()
}
