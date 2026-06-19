package com.airi.assistant.domain.customskill

import android.content.Context
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.TimeUnit

class CustomSkillExecutor(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun execute(skill: CustomSkill, input: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        // ── Guard: LOCAL skills not executable ────────────────────────────────
        if (skill.type == SkillType.LOCAL) {
            return@withContext SkillResult(false, "", "Local custom skills are not executable yet.", skill.name)
        }

        // ── Guard: endpoint validation ────────────────────────────────────────
        if (!CustomSkillSecurity.isValidEndpoint(skill.config.endpoint)) {
            val error = AppError.PolicyViolation("INVALID_CUSTOM_SKILL_URL", "Custom skill endpoint must be a valid http or https URL.")
            AppErrorHandler.log(error)
            AnalyticsService.skillFailed(skill.name, "invalid_url")
            return@withContext SkillResult(false, "", AppErrorHandler.toUserMessage(error), skill.name)
        }

        // ── Guard: circuit breaker ────────────────────────────────────────────
        if (!SkillCircuitBreaker.canExecute(skill.id)) {
            val remaining = SkillCircuitBreaker.remainingCooldownSeconds(skill.id)
            val msg = "Skill '${skill.name}' is temporarily disabled after repeated failures. " +
                    "It will be available again in ${remaining}s."
            AnalyticsService.skillFailed(skill.name, "circuit_open")
            return@withContext SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
        }

        // ── Guard: network ────────────────────────────────────────────────────
        val networkService = runCatching { ServiceLocator.networkService }.getOrNull()
        val isOnline = networkService?.isOnline() ?: true
        if (!isOnline) {
            val error = AppError.NetworkUnavailable("Custom skill '${skill.name}' requires an internet connection.")
            AppErrorHandler.log(error)
            AnalyticsService.skillFailed(skill.name, "offline")
            return@withContext SkillResult(false, "", AppErrorHandler.toUserMessage(error), skill.name)
        }

        // ── Build request ─────────────────────────────────────────────────────
        val method = skill.config.method.trim().uppercase(Locale.US).ifBlank { "POST" }

        // ── Guard: permission check ────────────────────────────────────────────
        val permission = skill.effectivePermission()
        when (val permCheck = SkillPermissionEnforcer.check(permission, method, isOnline)) {
            is SkillPermissionEnforcer.PermissionCheckResult.Denied -> {
                LoggingService.warn(TAG, "Permission denied for '${skill.name}': ${permCheck.reason}")
                AnalyticsService.skillFailed(skill.name, "permission_denied")
                return@withContext SkillResult(
                    false,
                    SkillExecutionOutput.failure(permCheck.reason).toJsonString(),
                    permCheck.reason,
                    skill.name
                )
            }
            is SkillPermissionEnforcer.PermissionCheckResult.Allowed -> Unit
        }

        val bodyInput = input.toMutableMap().apply {
            putIfAbsent("timestamp", System.currentTimeMillis())
        }
        val body = CustomSkillTemplateEngine.render(skill.config.bodyTemplate, bodyInput)
        val requestBody = if (method in setOf("GET", "HEAD")) null
        else body.toRequestBody("application/json; charset=utf-8".toMediaType())

        val sanitizedUrl = CustomSkillSecurity.sanitizeUrl(skill.config.endpoint)
        val maskedHeaders = CustomSkillSecurity.maskHeaders(skill.config.headers)
        LoggingService.info(TAG, "Executing '${skill.name}' method=$method url=$sanitizedUrl permission=$permission headers=$maskedHeaders")
        LoggingService.debug(TAG, "Request body (sanitized): ${body.take(500)}")

        // ── Semaphore: max 3 concurrent executions ────────────────────────────
        return@withContext executionSemaphore.withPermit {
            try {
                withTimeout(EXECUTION_TIMEOUT_MS) {
                    executeWithRetry(skill, method, requestBody, sanitizedUrl)
                }
            } catch (e: TimeoutCancellationException) {
                SkillCircuitBreaker.recordFailure(skill.id)
                AnalyticsService.skillFailed(skill.name, "timeout_cancelled")
                LoggingService.error(TAG, "Skill '${skill.name}' execution TIMED OUT after ${EXECUTION_TIMEOUT_MS}ms")
                val msg = "Skill execution timed out after 30 seconds. Check the endpoint and try again."
                SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
            }
        }
    }

    private suspend fun executeWithRetry(
        skill: CustomSkill,
        method: String,
        requestBody: okhttp3.RequestBody?,
        sanitizedUrl: String
    ): SkillResult {
        val maxAttempts = 3
        var lastNetworkError: Exception? = null

        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                val backoffMs = 500L * (1 shl (attempt - 1))
                LoggingService.info(TAG, "Retry $attempt for '${skill.name}' after ${backoffMs}ms")
                delay(backoffMs)
            }

            try {
                val requestBuilder = Request.Builder().url(skill.config.endpoint)
                skill.config.headers.forEach { (key, value) ->
                    if (key.isNotBlank() && value.isNotBlank()) requestBuilder.addHeader(key, value)
                }
                if (requestBody != null && skill.config.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                    requestBuilder.addHeader("Content-Type", "application/json")
                }

                val response = client.newCall(requestBuilder.method(method, requestBody).build()).execute()
                response.use { resp ->
                    val code = resp.code

                    // ── Response size limit ───────────────────────────────────
                    val contentLength = resp.body?.contentLength() ?: -1L
                    if (contentLength > MAX_RESPONSE_BYTES) {
                        val msg = "Response rejected: content-length ${contentLength / 1024}KB exceeds the 1MB limit."
                        LoggingService.warn(TAG, "Skill '${skill.name}' response too large: ${contentLength}B")
                        SkillCircuitBreaker.recordFailure(skill.id)
                        return SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
                    }

                    val rawBody = resp.body?.string().orEmpty()
                    val (responseBody, wasTruncated) = if (rawBody.length > MAX_RESPONSE_CHARS) {
                        LoggingService.warn(TAG, "Skill '${skill.name}' response truncated from ${rawBody.length} to $MAX_RESPONSE_CHARS chars")
                        rawBody.take(MAX_RESPONSE_CHARS) to true
                    } else {
                        rawBody to false
                    }

                    if (resp.isSuccessful) {
                        SkillCircuitBreaker.recordSuccess(skill.id)
                        var output = SkillExecutionOutput.success(responseBody)
                        if (wasTruncated) {
                            val truncatedData = (output.data?.toMutableMap() ?: mutableMapOf()).apply {
                                put("_warning", "Response was truncated to 1MB limit")
                            }
                            output = output.copy(data = truncatedData)
                        }
                        AnalyticsService.skillExecuted(skill.name)
                        LoggingService.info(TAG, "Skill '${skill.name}' succeeded status=$code attempt=${attempt + 1}${if (wasTruncated) " [truncated]" else ""}")
                        LoggingService.debug(TAG, "Normalized response: ${output.toJsonString().take(500)}")
                        return SkillResult(true, output.toJsonString(), null, skill.name)
                    }

                    // ── 4xx: client error — do not retry ─────────────────────
                    if (code in 400..499) {
                        val message = "HTTP $code: ${responseBody.take(300)}"
                        val output = SkillExecutionOutput.failure(message)
                        SkillCircuitBreaker.recordFailure(skill.id)
                        AnalyticsService.skillFailed(skill.name, "http_$code")
                        LoggingService.warn(TAG, "Skill '${skill.name}' client error status=$code (not retrying)")
                        return SkillResult(false, output.toJsonString(), message, skill.name)
                    }

                    // ── 5xx: server error — retry ─────────────────────────────
                    val serverError = "HTTP $code: ${responseBody.take(300)}"
                    LoggingService.warn(TAG, "Skill '${skill.name}' server error status=$code attempt=${attempt + 1}")
                    if (attempt == maxAttempts - 1) {
                        SkillCircuitBreaker.recordFailure(skill.id)
                        AnalyticsService.skillFailed(skill.name, "http_$code")
                        val output = SkillExecutionOutput.failure(serverError)
                        return SkillResult(false, output.toJsonString(), serverError, skill.name)
                    }
                }

            } catch (e: SocketTimeoutException) {
                lastNetworkError = e
                LoggingService.warn(TAG, "Skill '${skill.name}' socket timeout attempt=${attempt + 1}: ${e.message}")
                if (attempt == maxAttempts - 1) {
                    logCrashlytics("Custom skill socket timeout: ${skill.name}", e)
                    SkillCircuitBreaker.recordFailure(skill.id)
                    AnalyticsService.skillFailed(skill.name, "socket_timeout")
                    LoggingService.error(TAG, "Skill '${skill.name}' exhausted retries (socket timeout)", e)
                    val msg = "Connection timed out after $maxAttempts attempts. Check the endpoint and your connection."
                    return SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
                }

            } catch (e: IOException) {
                lastNetworkError = e
                LoggingService.warn(TAG, "Skill '${skill.name}' IO error attempt=${attempt + 1}: ${e.message}")
                if (attempt == maxAttempts - 1) {
                    logCrashlytics("Custom skill IO error: ${skill.name}", e)
                    SkillCircuitBreaker.recordFailure(skill.id)
                    AnalyticsService.skillFailed(skill.name, "io_error")
                    LoggingService.error(TAG, "Skill '${skill.name}' exhausted retries (IO error)", e)
                    val error = AppErrorHandler.capture(e, "CustomSkillExecutor[${skill.name}]")
                    val msg = AppErrorHandler.toUserMessage(error)
                    return SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
                }

            } catch (e: Exception) {
                logCrashlytics("Custom skill failed: ${skill.name}", e)
                SkillCircuitBreaker.recordFailure(skill.id)
                val error = AppErrorHandler.capture(e, "CustomSkillExecutor[${skill.name}]")
                AnalyticsService.skillFailed(skill.name, "exception")
                LoggingService.error(TAG, "Skill '${skill.name}' non-retryable error", e)
                val msg = AppErrorHandler.toUserMessage(error)
                return SkillResult(false, SkillExecutionOutput.failure(msg).toJsonString(), msg, skill.name)
            }
        }

        val fallbackMsg = lastNetworkError?.message ?: "Network error after $maxAttempts attempts"
        SkillCircuitBreaker.recordFailure(skill.id)
        return SkillResult(false, SkillExecutionOutput.failure(fallbackMsg).toJsonString(), fallbackMsg, skill.name)
    }

    private fun logCrashlytics(message: String, throwable: Throwable) {
        runCatching {
            val cls = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
            val instance = cls.getMethod("getInstance").invoke(null)
            cls.getMethod("log", String::class.java).invoke(instance, message)
            cls.getMethod("recordException", Throwable::class.java).invoke(instance, throwable)
        }
    }

    private companion object {
        private const val TAG = "CustomSkillExecutor"
        private const val EXECUTION_TIMEOUT_MS = 30_000L
        private const val MAX_RESPONSE_BYTES = 1_048_576L
        private const val MAX_RESPONSE_CHARS = 1_048_576

        val executionSemaphore = Semaphore(3)
    }
}
