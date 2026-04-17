package com.airi.assistant.domain.error

import com.airi.assistant.domain.logging.LoggingService

object AppErrorHandler {

    private const val TAG = "AppErrorHandler"

    fun capture(throwable: Throwable, context: String = ""): AppError {
        val message = throwable.localizedMessage ?: throwable.message ?: "Unknown error"
        val fullMsg = if (context.isNotBlank()) "[$context] $message" else message
        val error = AppError.Unknown(fullMsg, throwable)
        log(error)
        return error
    }

    fun handle(error: AppError): String {
        log(error)
        return toUserMessage(error)
    }

    fun log(error: AppError) {
        val msg = "[${error::class.simpleName}] ${error.message}"
        if (error.cause != null) {
            LoggingService.error(TAG, msg, error.cause)
        } else {
            LoggingService.error(TAG, msg)
        }
    }

    fun toUserMessage(error: AppError): String = when (error) {
        is AppError.NetworkUnavailable ->
            "No internet connection. Please check your network and try again."
        is AppError.AuthenticationFailed ->
            "Authentication failed: ${error.message}"
        is AppError.PermissionDenied ->
            "Permission denied: ${error.permission}. Please grant access in settings."
        is AppError.PolicyViolation ->
            error.message
        is AppError.AgentExecutionFailed ->
            "Agent failed: ${error.message}"
        is AppError.SkillExecutionFailed ->
            "Skill '${error.skillName}' failed: ${error.message}"
        is AppError.RateLimitExceeded ->
            error.message
        is AppError.Unknown ->
            error.message
    }
}
