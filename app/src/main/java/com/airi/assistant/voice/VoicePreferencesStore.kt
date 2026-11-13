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
    private const val KEY_CLOUD_PROVIDER = "cloud_voice_provider"
    private const val LEGACY_PREFS_NAME = "airi_voice"
    private const val LEGACY_HOTWORD_ENABLED = "ok_airi_enabled"
    private const val LEGACY_VOICE_ENABLED = "voice_enabled"

    /**
     * Stable preference identifiers and acoustic values only.
     * User-facing labels and descriptions belong to localized UI resources.
     */
    enum class PersonalityPreset(
        val pitch: Float,
        val rate: Float
    ) {
        STANDARD(0.95f, 1.05f),
        CALM(0.85f, 0.85f),
        ENERGETIC(1.10f, 1.20f),
        FORMAL(0.90f, 0.90f),
        PLAYFUL(1.20f, 1.10f)
    }

    data class Snapshot(
        val pitch: Float,
        val rate: Float,
        val voiceName: String,
        val preset: PersonalityPreset,
        val voiceEnabled: Boolean,
        val hotwordEnabled: Boolean,
        val cloudVoiceProvider: String = "LOCAL"
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
        val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val voiceEnabled = if (prefs.contains(KEY_VOICE_ENABLED)) {
            prefs.getBoolean(KEY_VOICE_ENABLED, false)
        } else {
            legacy.getBoolean(LEGACY_VOICE_ENABLED, false)
        }
        val hotwordEnabled = if (prefs.contains(KEY_HOTWORD_ENABLED)) {
            prefs.getBoolean(KEY_HOTWORD_ENABLED, false)
        } else {
            legacy.getBoolean(LEGACY_HOTWORD_ENABLED, false)
        }
        val cloudProvider = if (prefs.contains(KEY_CLOUD_PROVIDER)) {
            prefs.getString(KEY_CLOUD_PROVIDER, "LOCAL") ?: "LOCAL"
        } else {
            legacy.getString(KEY_CLOUD_PROVIDER, "LOCAL") ?: "LOCAL"
        }
        // One-time compatibility migration. Existing installations keep their wake-word
        // and provider choices while all future readers use this store only.
        if (!prefs.contains(KEY_HOTWORD_ENABLED) || !prefs.contains(KEY_CLOUD_PROVIDER)) {
            prefs.edit()
                .putBoolean(KEY_VOICE_ENABLED, voiceEnabled)
                .putBoolean(KEY_HOTWORD_ENABLED, hotwordEnabled)
                .putString(KEY_CLOUD_PROVIDER, cloudProvider)
                .apply()
        }
        return Snapshot(pitch, rate, voice, preset, voiceEnabled, hotwordEnabled, cloudProvider).also {
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
        hotwordEnabled: Boolean,
        cloudVoiceProvider: String = (_snapshot.value?.cloudVoiceProvider ?: "LOCAL")
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_PITCH, pitch)
            .putFloat(KEY_RATE,  rate)
            .putString(KEY_VOICE, voiceName)
            .putString(KEY_PRESET, preset.name)
            .putBoolean(KEY_VOICE_ENABLED, voiceEnabled)
            .putBoolean(KEY_HOTWORD_ENABLED, hotwordEnabled)
            .putString(KEY_CLOUD_PROVIDER, cloudVoiceProvider)
            .apply()
        _snapshot.value = Snapshot(pitch, rate, voiceName, preset, voiceEnabled, hotwordEnabled, cloudVoiceProvider)
    }

    /** Update runtime switches without overwriting the user's TTS personalization. */
    fun updateFlags(
        context: Context,
        voiceEnabled: Boolean? = null,
        hotwordEnabled: Boolean? = null,
        cloudVoiceProvider: String? = null
    ): Snapshot {
        val current = _snapshot.value ?: load(context)
        val next = current.copy(
            voiceEnabled = voiceEnabled ?: current.voiceEnabled,
            hotwordEnabled = hotwordEnabled ?: current.hotwordEnabled,
            cloudVoiceProvider = cloudVoiceProvider ?: current.cloudVoiceProvider
        )
        save(context, next.pitch, next.rate, next.voiceName, next.preset,
            next.voiceEnabled, next.hotwordEnabled, next.cloudVoiceProvider)
        return next
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
