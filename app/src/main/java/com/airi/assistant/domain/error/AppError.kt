package com.airi.assistant.domain.error

sealed class AppError(val message: String, val cause: Throwable? = null) {

    class NetworkUnavailable(
        message: String = "No internet connection available"
    ) : AppError(message)

    class AuthenticationFailed(
        message: String,
        cause: Throwable? = null
    ) : AppError(message, cause)

    class PermissionDenied(
        val permission: String,
        message: String
    ) : AppError(message)

    class PolicyViolation(
        val rule: String,
        message: String
    ) : AppError(message)

    class AgentExecutionFailed(
        message: String,
        cause: Throwable? = null
    ) : AppError(message, cause)

    class SkillExecutionFailed(
        val skillName: String,
        message: String,
        cause: Throwable? = null
    ) : AppError(message, cause)

    class RateLimitExceeded(
        message: String = "Too many requests. Please wait before trying again."
    ) : AppError(message)

    class Unknown(
        message: String,
        cause: Throwable? = null
    ) : AppError(message, cause)
}
