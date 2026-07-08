package com.airi.assistant.ai.remote

data class RemoteModel(
    val id: String,
    val name: String,
    val serverUrl: String,
    val apiKey: String = "",
    val isActive: Boolean = false,
    /**
     * B-07: Marks user-configured custom OpenAI-compatible endpoints.
     * Custom endpoints must never be removed by stale-model-name migrations,
     * because their IDs are user-defined and not known to the migration logic.
     */
    val isCustomEndpoint: Boolean = false
)
