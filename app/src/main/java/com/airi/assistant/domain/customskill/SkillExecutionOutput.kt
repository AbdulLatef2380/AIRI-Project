package com.airi.assistant.domain.customskill

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class SkillExecutionOutput(
    val success: Boolean,
    val data: Map<String, Any>?,
    val error: String?
) {
    fun toJsonString(): String = Gson().toJson(this)

    companion object {
        private val gson = Gson()
        private val mapType = object : TypeToken<Map<String, Any>>() {}.type

        fun fromRawResponse(responseBody: String): Map<String, Any> {
            return runCatching<Map<String, Any>> {
                gson.fromJson(responseBody, mapType)
            }.getOrElse {
                mapOf("raw" to responseBody.take(2000))
            }
        }

        fun success(responseBody: String): SkillExecutionOutput {
            val parsed = fromRawResponse(responseBody)
            return SkillExecutionOutput(success = true, data = parsed, error = null)
        }

        fun failure(error: String): SkillExecutionOutput =
            SkillExecutionOutput(success = false, data = null, error = error)
    }
}
