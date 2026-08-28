package com.airi.assistant.voice

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLocalePolicyTest {

    @Test
    fun acceptsAnOnDeviceVoiceForTheSameLanguageAcrossRegions() {
        assertTrue(
            VoiceLocalePolicy.isOnDeviceVoiceFor(
                appLocale = Locale("ar", "SA"),
                voiceLocale = Locale("ar", "EG"),
                requiresNetwork = false
            )
        )
    }

    @Test
    fun rejectsNetworkVoiceEvenWhenItMatchesTheAppLanguage() {
        assertFalse(
            VoiceLocalePolicy.isOnDeviceVoiceFor(
                appLocale = Locale.US,
                voiceLocale = Locale.UK,
                requiresNetwork = true
            )
        )
    }

    @Test
    fun rejectsDifferentOrBlankLanguages() {
        assertFalse(
            VoiceLocalePolicy.isOnDeviceVoiceFor(
                appLocale = Locale("ar"),
                voiceLocale = Locale.ENGLISH,
                requiresNetwork = false
            )
        )
        assertFalse(
            VoiceLocalePolicy.isOnDeviceVoiceFor(
                appLocale = Locale.ROOT,
                voiceLocale = Locale.ENGLISH,
                requiresNetwork = false
            )
        )
    }
}
