package com.airi.assistant.domain.tool

/** Canonical description of a tool before any Android/network implementation is attached. */
data class ToolSpec(
    val id: String,
    val arguments: List<ArgumentSpec> = emptyList(),
    val capability: String,
    val risk: Risk,
    val privacy: PrivacyBoundary,
    val requiresConfirmation: Boolean,
    val idempotent: Boolean,
    val handlerKey: String,
) {
    init {
        require(id.isNotBlank()) { "tool id must not be blank" }
        require(id == id.trim() && !id.contains(' ')) { "tool id must be stable and space-free" }
        require(handlerKey.isNotBlank()) { "handlerKey must not be blank" }
        require(arguments.map { it.name }.distinct().size == arguments.size) { "duplicate argument names" }
    }
    enum class Risk { READ, SIDE_EFFECT, DESTRUCTIVE }
    enum class PrivacyBoundary { LOCAL_ONLY, CLOUD_ALLOWED, USER_APPROVAL_REQUIRED }
}

data class ArgumentSpec(
    val name: String,
    val type: Type,
    val required: Boolean,
    val constraints: Constraints = Constraints(),
) {
    init { require(name.isNotBlank() && name == name.trim()) }
    enum class Type { STRING, INTEGER, BOOLEAN, URL, DATE, ENUM }
    data class Constraints(
        val minLength: Int? = null,
        val maxLength: Int? = null,
        val minNumber: Long? = null,
        val maxNumber: Long? = null,
        val allowedValues: Set<String> = emptySet(),
        val allowedSchemes: Set<String> = emptySet(),
    ) {
        init {
            require(minLength == null || minLength >= 0) { "minLength must be non-negative" }
            require(maxLength == null || maxLength >= 0) { "maxLength must be non-negative" }
            require(minLength == null || maxLength == null || minLength <= maxLength) { "minLength must not exceed maxLength" }
            require(minNumber == null || maxNumber == null || minNumber <= maxNumber) { "minNumber must not exceed maxNumber" }
            require(allowedSchemes.all { it.isNotBlank() && it == it.lowercase() }) { "URL schemes must be lowercase and non-blank" }
        }
    }
}

sealed interface AuthorizationDecision {
    data object Allow : AuthorizationDecision
    data class Deny(val reason: DenyReason) : AuthorizationDecision
    data class NeedsApproval(val request: ApprovalRequest) : AuthorizationDecision
}

enum class DenyReason { UNKNOWN_TOOL, INVALID_ARGUMENTS, MISSING_CAPABILITY, PERMISSION_DENIED, NOT_READY, POLICY_DENIED, PRIVACY_DENIED, RATE_LIMITED }

data class ApprovalRequest(
    val token: String,
    val principal: String,
    val toolId: String,
    val canonicalArguments: String,
    val sessionId: String,
    val expiresAtEpochMs: Long,
)
