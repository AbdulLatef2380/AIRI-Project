package com.airi.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * VoicePreferencesStore — singleton for TTS personalization settings.
 *
 * Persists pitch, speech rate, selected voice name, and personality preset
 * in SharedPreferences (file: airi_voice_prefs). The IncrementalTtsEngine
 * calls [apply] during init so every TTS session picks up the latest settings.
 */
object VoicePreferencesStore {

    private const val PREFS_NAME     = "airi_voice_prefs"
    private const val KEY_PITCH      = "tts_pitch"
    private const val KEY_RATE       = "tts_rate"
    private const val KEY_VOICE      = "tts_voice_name"
    private const val KEY_PRESET     = "tts_preset"
    private const val KEY_VOICE_ENABLED  = "voice_enabled"
    private const val KEY_HOTWORD_ENABLED = "hotword_enabled"

    enum class PersonalityPreset(
        val label: String,
        val emoji: String,
        val pitch: Float,
        val rate: Float,
        val description: String
    ) {
        STANDARD("Standard",  "🤖", 0.95f, 1.05f, "Default AIRI voice — balanced and clear"),
        CALM    ("Calm",      "🌿", 0.85f, 0.85f, "Slow and measured — great for focus"),
        ENERGETIC("Energetic","⚡", 1.10f, 1.20f, "Upbeat and fast — best for quick tasks"),
        FORMAL  ("Formal",   "🎓", 0.90f, 0.90f, "Professional and deliberate"),
        PLAYFUL ("Playful",  "🎉", 1.20f, 1.10f, "Light and expressive — casual use")
    }

    data class Snapshot(
        val pitch: Float,
        val rate: Float,
        val voiceName: String,
        val preset: PersonalityPreset,
        val voiceEnabled: Boolean,
        val hotwordEnabled: Boolean
    )

    private val _snapshot = MutableStateFlow<Snapshot?>(null)
    val snapshotFlow: StateFlow<Snapshot?> = _snapshot.asStateFlow()

    /** Load stored preferences. Call once after app start. */
    fun load(context: Context): Snapshot {
        val prefs   = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val pitch   = prefs.getFloat(KEY_PITCH, PersonalityPreset.STANDARD.pitch)
        val rate    = prefs.getFloat(KEY_RATE,  PersonalityPreset.STANDARD.rate)
        val voice   = prefs.getString(KEY_VOICE, "") ?: ""
        val preset  = runCatching {
            PersonalityPreset.valueOf(prefs.getString(KEY_PRESET, PersonalityPreset.STANDARD.name)!!)
        }.getOrDefault(PersonalityPreset.STANDARD)
        val voiceEnabled   = prefs.getBoolean(KEY_VOICE_ENABLED, false)
        val hotwordEnabled = prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        return Snapshot(pitch, rate, voice, preset, voiceEnabled, hotwordEnabled).also {
            _snapshot.value = it
        }
    }

    fun save(
        context:        Context,
        pitch:          Float,
        rate:           Float,
        voiceName:      String,
        preset:         PersonalityPreset,
        voiceEnabled:   Boolean,
        hotwordEnabled: Boolean
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PITCH, pitch)
            .putFloat(KEY_RATE,  rate)
            .putString(KEY_VOICE, voiceName)
            .putString(KEY_PRESET, preset.name)
            .putBoolean(KEY_VOICE_ENABLED, voiceEnabled)
            .putBoolean(KEY_HOTWORD_ENABLED, hotwordEnabled)
            .apply()
        _snapshot.value = Snapshot(pitch, rate, voiceName, preset, voiceEnabled, hotwordEnabled)
    }

    /**
     * Apply stored pitch and rate to a live [TextToSpeech] instance.
     * Call from [IncrementalTtsEngine.init] after TTS reports SUCCESS.
     */
    fun apply(context: Context, tts: TextToSpeech) {
        val s = _snapshot.value ?: load(context)
        tts.setSpeechRate(s.rate)
        tts.setPitch(s.pitch)
        if (s.voiceName.isNotBlank()) {
            tts.voices?.firstOrNull { it.name == s.voiceName }?.let { tts.voice = it }
        }
    }

    fun currentPitch(context: Context): Float =
        (_snapshot.value ?: load(context)).pitch

    fun currentRate(context: Context): Float =
        (_snapshot.value ?: load(context)).rate
}
