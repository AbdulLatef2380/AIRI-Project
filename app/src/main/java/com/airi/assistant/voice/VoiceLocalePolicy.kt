package com.airi.assistant.voice

import java.util.Locale

/**
 * Selects system TTS voices that can serve the active application language.
 *
 * This policy deliberately has no dependency on Android's [android.speech.tts.Voice]
 * so its language and offline guarantees are covered by JVM tests.
 */
internal object VoiceLocalePolicy {

    /**
     * A voice belongs in the personalization list only when it does not require a
     * network connection and it speaks the same language as the active app locale.
     * Region differences are accepted because TTS engines often ship regional voices
     * without a generic language voice.
     */
    fun isOnDeviceVoiceFor(
        appLocale: Locale,
        voiceLocale: Locale,
        requiresNetwork: Boolean
    ): Boolean = !requiresNetwork &&
        appLocale.language.isNotBlank() &&
        appLocale.language.equals(voiceLocale.language, ignoreCase = true)
}
