package com.airi.assistant.ai

import android.content.Context
import android.content.SharedPreferences

class ModelConfigManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("airi_model_config", Context.MODE_PRIVATE)

    data class ModelConfig(
        val modelId: String,
        val displayName: String        = "",
        val bosEnabled: Boolean        = true,
        val eosEnabled: Boolean        = true,
        val generationPromptEnabled: Boolean = true,
        val systemPrompt: String       = "",
        val template: String           = "",
        val stopWords: List<String>    = emptyList(),
        val temperature: Float         = 0.7f,
        val topP: Float                = 0.9f,
        val maxTokens: Int             = 512
    )

    fun getConfig(modelId: String): ModelConfig {
        val key = sanitize(modelId)
        return ModelConfig(
            modelId                  = modelId,
            displayName              = prefs.getString("${key}_name", "") ?: "",
            bosEnabled               = prefs.getBoolean("${key}_bos", true),
            eosEnabled               = prefs.getBoolean("${key}_eos", true),
            generationPromptEnabled  = prefs.getBoolean("${key}_gen_prompt", true),
            systemPrompt             = prefs.getString("${key}_sys_prompt", "") ?: "",
            template                 = prefs.getString("${key}_template", "") ?: "",
            stopWords                = prefs.getString("${key}_stop_words", "")
                ?.split("|")?.filter { it.isNotBlank() } ?: emptyList(),
            temperature              = prefs.getFloat("${key}_temperature", 0.7f),
            topP                     = prefs.getFloat("${key}_top_p", 0.9f),
            maxTokens                = prefs.getInt("${key}_max_tokens", 512)
        )
    }

    fun saveConfig(config: ModelConfig) {
        val key = sanitize(config.modelId)
        prefs.edit().apply {
            putString("${key}_name",        config.displayName)
            putBoolean("${key}_bos",        config.bosEnabled)
            putBoolean("${key}_eos",        config.eosEnabled)
            putBoolean("${key}_gen_prompt", config.generationPromptEnabled)
            putString("${key}_sys_prompt",  config.systemPrompt)
            putString("${key}_template",    config.template)
            putString("${key}_stop_words",  config.stopWords.joinToString("|"))
            putFloat("${key}_temperature",  config.temperature)
            putFloat("${key}_top_p",        config.topP)
            putInt("${key}_max_tokens",     config.maxTokens)
        }.apply()
    }

    fun getPerformanceMode(): PerformanceMode {
        val name = prefs.getString("performance_mode", PerformanceMode.BALANCED.name)
            ?: PerformanceMode.BALANCED.name
        return runCatching { PerformanceMode.valueOf(name) }.getOrDefault(PerformanceMode.BALANCED)
    }

    fun setPerformanceMode(mode: PerformanceMode) {
        prefs.edit().putString("performance_mode", mode.name).apply()
    }

    private fun sanitize(id: String) = id.replace(Regex("[^a-zA-Z0-9_]"), "_")
}
