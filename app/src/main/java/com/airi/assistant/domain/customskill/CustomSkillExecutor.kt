package com.airi.assistant.domain.customskill

import android.content.Context
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.logging.LoggingService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
        if (skill.type == SkillType.LOCAL) {
            return@withContext SkillResult(false, "", "Local custom skills are not executable yet.", skill.name)
        }

        if (!CustomSkillSecurity.isValidEndpoint(skill.config.endpoint)) {
            val error = AppError.PolicyViolation("INVALID_CUSTOM_SKILL_URL", "Custom skill endpoint must be a valid http or https URL.")
            AppErrorHandler.log(error)
            AnalyticsService.skillFailed(skill.name, "invalid_url")
            return@withContext SkillResult(false, "", AppErrorHandler.toUserMessage(error), skill.name)
        }

        val networkService = runCatching { ServiceLocator.networkService }.getOrNull()
        if (networkService?.isOnline() == false) {
            val error = AppError.NetworkUnavailable("Custom skill '${skill.name}' requires an internet connection.")
            AppErrorHandler.log(error)
            AnalyticsService.skillFailed(skill.name, "offline")
            return@withContext SkillResult(false, "", AppErrorHandler.toUserMessage(error), skill.name)
        }

        val method = skill.config.method.trim().uppercase(Locale.US).ifBlank { "POST" }
        val bodyInput = input.toMutableMap().apply {
            putIfAbsent("timestamp", System.currentTimeMillis())
            putIfAbsent("user_id", FirebaseAuth.getInstance().currentUser?.uid.orEmpty())
        }
        val body = CustomSkillTemplateEngine.render(skill.config.bodyTemplate, bodyInput)
        val requestBody = if (method in setOf("GET", "HEAD")) null else body.toRequestBody("application/json; charset=utf-8".toMediaType())

        LoggingService.info(
            TAG,
            "Executing custom skill '${skill.name}' method=$method url=${CustomSkillSecurity.sanitizeUrl(skill.config.endpoint)} headers=${CustomSkillSecurity.maskHeaders(skill.config.headers)}"
        )

        return@withContext try {
            val requestBuilder = Request.Builder().url(skill.config.endpoint)
            skill.config.headers.forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) requestBuilder.addHeader(key, value)
            }
            if (requestBody != null && skill.config.headers.keys.none { it.equals("Content-Type", ignoreCase = true) }) {
                requestBuilder.addHeader("Content-Type", "application/json")
            }
            val response = client.newCall(requestBuilder.method(method, requestBody).build()).execute()
            response.use {
                val responseBody = it.body?.string().orEmpty()
                if (it.isSuccessful) {
                    AnalyticsService.skillExecuted(skill.name)
                    LoggingService.info(TAG, "Custom skill '${skill.name}' completed status=${it.code}")
                    SkillResult(true, responseBody, null, skill.name)
                } else {
                    val message = "HTTP ${it.code}: ${responseBody.take(300)}"
                    AnalyticsService.skillFailed(skill.name, "http_${it.code}")
                    LoggingService.warn(TAG, "Custom skill '${skill.name}' failed status=${it.code}")
                    SkillResult(false, "", message, skill.name)
                }
            }
        } catch (e: SocketTimeoutException) {
            logCrashlytics("Custom skill timeout: ${skill.name}", e)
            AnalyticsService.skillFailed(skill.name, "timeout")
            LoggingService.error(TAG, "Custom skill '${skill.name}' timed out", e)
            SkillResult(false, "", "Custom skill timed out. Please try again.", skill.name)
        } catch (e: Exception) {
            logCrashlytics("Custom skill failed: ${skill.name}", e)
            val error = AppErrorHandler.capture(e, "CustomSkillExecutor[${skill.name}]")
            AnalyticsService.skillFailed(skill.name, "exception")
            SkillResult(false, "", AppErrorHandler.toUserMessage(error), skill.name)
        }
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
    }
}