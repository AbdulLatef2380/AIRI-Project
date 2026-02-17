package com.airi.assistant.tools

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * محرك تنفيذ الأدوات (Tool Executor)
 * يدعم تنفيذ الأدوات عبر الشبكة (HTTP) أو محلياً (Shell Commands).
 */
object ToolExecutor {
    private const val TAG = "ToolExecutor"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * تنفيذ الأداة بناءً على نوعها (HTTP أو Command)
     */
    fun execute(tool: ToolDefinition, params: Map<String, Any>): String {
        return try {
            when {
                tool.endpoint != null -> executeHttpRequest(tool, params)
                tool.command != null -> executeShellCommand(tool, params)
                else -> "Error: Tool has no endpoint or command defined."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execution failed for tool ${tool.name}: ${e.message}")
            "Error: ${e.message}"
        }
    }

    /**
     * تنفيذ طلب HTTP (للأدوات الخارجية مثل n8n)
     */
    private fun executeHttpRequest(tool: ToolDefinition, params: Map<String, Any>): String {
        val bodyContent = JSONObject(params).toString()
        val requestBuilder = Request.Builder().url(tool.endpoint!!)

        if (tool.method?.equals("POST", ignoreCase = true) == true) {
            requestBuilder.post(bodyContent.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            requestBuilder.get()
        }

        val response = client.newCall(requestBuilder.build()).execute()
        return response.body?.string() ?: "Empty response"
    }

    /**
     * تنفيذ أمر نظام محلي (Shell Command)
     */
    private fun executeShellCommand(tool: ToolDefinition, params: Map<String, Any>): String {
        var finalCommand = tool.command!!
        
        // استبدال المعاملات في الأمر (مثال: {text} -> قيمة المعامل)
        params.forEach { (key, value) ->
            finalCommand = finalCommand.replace("{$key}", value.toString())
        }

        Log.d(TAG, "Executing shell command: $finalCommand")
        val process = Runtime.getRuntime().exec(finalCommand)
        val output = process.inputStream.bufferedReader().readText()
        val error = process.errorStream.bufferedReader().readText()

        return if (error.isNotEmpty()) "Error: $error" else output
    }
}
