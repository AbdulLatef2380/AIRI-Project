package com.airi.assistant.tools

import com.google.gson.annotations.SerializedName

/**
 * تعريف قياسي للأداة (Tool Schema)
 * يسمح لـ AIRI بفهم كيفية استدعاء الأداة وما هي المعاملات المطلوبة.
 */
data class ToolDefinition(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String,
    @SerializedName("parameters") val parameters: Map<String, String>, // "param_name" to "type"
    @SerializedName("endpoint") val endpoint: String? = null, // للطلبات الشبكية (HTTP)
    @SerializedName("method") val method: String? = "POST",
    @SerializedName("command") val command: String? = null, // لأوامر النظام المحلية (Shell)
    @SerializedName("permissions") val permissions: List<String> = emptyList(),
    @SerializedName("version") val version: String = "1.0.0"
)
