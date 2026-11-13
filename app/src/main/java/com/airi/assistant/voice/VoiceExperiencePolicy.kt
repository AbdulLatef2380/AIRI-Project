package com.airi.assistant.voice

/**
 * User-facing voice experience controls. Values are stable identifiers so they
 * can be persisted safely and localized only at the UI layer.
 */
object VoiceExperiencePolicy {
    enum class ResponseStyle(val maxSpeechCharacters: Int, val maxSentences: Int) {
        CONCISE(900, 3),
        BALANCED(1_800, 6),
        DETAILED(3_600, 12),
    }

    enum class AlertSounds { OFF, SUBTLE, DISTINCT }

    fun parseResponseStyle(value: String?): ResponseStyle = runCatching {
        ResponseStyle.valueOf(value.orEmpty())
    }.getOrDefault(ResponseStyle.BALANCED)

    fun parseAlertSounds(value: String?): AlertSounds = runCatching {
        AlertSounds.valueOf(value.orEmpty())
    }.getOrDefault(AlertSounds.SUBTLE)
}
