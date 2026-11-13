package com.airi.assistant.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

/** Short system tones only; it never records or logs audio content. */
object VoiceFeedbackPlayer {
    private const val STREAM = AudioManager.STREAM_NOTIFICATION
    private const val DURATION_MS = 90

    fun playReady(context: Context) = play(context, ToneGenerator.TONE_PROP_BEEP)

    fun playError(context: Context) = play(context, ToneGenerator.TONE_PROP_NACK)

    private fun play(context: Context, tone: Int) {
        val prefs = VoicePreferencesStore.load(context)
        val volume = when (prefs.alertSounds) {
            VoiceExperiencePolicy.AlertSounds.OFF -> return
            VoiceExperiencePolicy.AlertSounds.SUBTLE -> 35
            VoiceExperiencePolicy.AlertSounds.DISTINCT -> 70
        }
        val generator = runCatching { ToneGenerator(STREAM, volume) }.getOrNull() ?: return
        try {
            generator.startTone(tone, DURATION_MS)
        } finally {
            generator.release()
        }
    }
}
