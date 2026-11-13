package com.airi.assistant.voice.realtime

/**
 * Resolves the persisted voice provider identifier without touching Android
 * preferences, credentials, sockets, or UI. Unknown and local identifiers fail
 * closed to the on-device pipeline.
 */
object RealtimeProviderFactory {
    fun create(identifier: String?): RealtimeVoiceProvider = when (normalize(identifier)) {
        "gemini", "gemini_live", "google" -> GeminiLiveProvider()
        "openai", "openai_realtime" -> OpenAIRealtimeProvider()
        else -> LocalVoicePipeline
    }

    fun isCloud(identifier: String?): Boolean = create(identifier) !== LocalVoicePipeline

    private fun normalize(identifier: String?): String =
        identifier.orEmpty().trim().lowercase().replace('-', '_').replace(' ', '_')
}
