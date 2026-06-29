package com.airi.assistant.settings

import android.content.Context
import android.util.Log
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.prefs.ExecModePreferences
import com.airi.assistant.voice.VoicePreferencesStore
import kotlinx.coroutines.flow.StateFlow

/**
 * PreferenceCoordinator — unified facade over AIRI's five preference stores.
 *
 * ── Phase 2, Task 6 ────────────────────────────────────────────────────────
 * AIRI previously had 5 independent preference stores with no common facade:
 *
 *   1. [ExecModePreferences]         — encrypted execution / privacy settings
 *   2. [VoicePreferencesStore]       — TTS pitch, rate, hotword, preset
 *   3. UserProfileRepository         — user identity and AI persona settings
 *   4. SharedPreferencesSnapshotStore — execution graph state snapshots
 *   5. Theme/customisation SharedPreferences — dark mode, accent colour
 *
 * Problems:
 *   - No "Reset to Defaults" covering all stores.
 *   - Consumers import multiple stores independently, creating split-brain risk.
 *   - No unified observability of the preference state.
 *
 * Solution:
 *   [PreferenceCoordinator] acts as a single-import facade. All reads and
 *   writes delegate to the appropriate backing store. The coordinator itself
 *   is registered in [com.airi.assistant.core.ServiceLocator] so every
 *   consumer gets the same instance.
 *
 * ── What is NOT unified ────────────────────────────────────────────────────
 * [UserProfileRepository] involves Firestore sync and is exposed via
 * [com.airi.assistant.core.ServiceLocator.userProfileRepository] directly;
 * future iterations can wrap it here when its API stabilises.
 *
 * ── Thread safety ──────────────────────────────────────────────────────────
 * [ExecModePreferences] is synchronous (in-memory after init). [VoicePreferencesStore]
 * uses SharedPreferences which is thread-safe. All operations on this class
 * are safe to call from any thread.
 */
class PreferenceCoordinator(
    private val context:        Context,
    private val execPrefs:      ExecModePreferences,
) {

    /**
     * Task 12: Expose the backing [ExecModePreferences] instance so
     * [com.airi.assistant.core.ServiceLocator] can surface it as
     * [ServiceLocator.execModePrefs] without breaking encapsulation inside
     * this coordinator. Consumers should prefer the typed properties on
     * [PreferenceCoordinator] itself; only use [rawExecPrefs] when the full
     * [ExecModePreferences] object is required (e.g. for passing to
     * [HybridOrchestrator] or [RuntimeRouter]).
     */
    val rawExecPrefs: ExecModePreferences get() = execPrefs

    // ── Execution preferences ─────────────────────────────────────────────────

    var executionMode: ExecutionMode
        get() = execPrefs.executionMode
        set(value) { execPrefs.executionMode = value }

    var privacyLevel: PrivacyLevel
        get() = execPrefs.privacyLevel
        set(value) { execPrefs.privacyLevel = value }

    var preferredProvider: CloudProvider
        get() = execPrefs.preferredProvider
        set(value) { execPrefs.preferredProvider = value }

    var internetPermissionGranted: Boolean
        get() = execPrefs.internetPermissionGranted
        set(value) { execPrefs.internetPermissionGranted = value }

    var offlineFallbackEnabled: Boolean
        get() = execPrefs.offlineFallbackEnabled
        set(value) { execPrefs.offlineFallbackEnabled = value }

    var maxDailyCloudTokens: Int
        get() = execPrefs.maxDailyCloudTokens
        set(value) { execPrefs.maxDailyCloudTokens = value }

    val effectiveExecutionMode: ExecutionMode
        get() = execPrefs.effectiveMode

    val isExecPrefsEncrypted: Boolean
        get() = execPrefs.isEncrypted

    // ── Voice preferences ─────────────────────────────────────────────────────

    /** Current voice settings snapshot, or null if [loadVoicePrefs] has not been called. */
    val voiceSnapshot: StateFlow<VoicePreferencesStore.Snapshot?> =
        VoicePreferencesStore.snapshotFlow

    /** Load and return the persisted voice snapshot. */
    fun loadVoicePrefs(): VoicePreferencesStore.Snapshot =
        VoicePreferencesStore.load(context)

    fun saveVoicePrefs(
        pitch:          Float,
        rate:           Float,
        voiceName:      String,
        preset:         VoicePreferencesStore.PersonalityPreset,
        voiceEnabled:   Boolean,
        hotwordEnabled: Boolean
    ) = VoicePreferencesStore.save(
        context        = context,
        pitch          = pitch,
        rate           = rate,
        voiceName      = voiceName,
        preset         = preset,
        voiceEnabled   = voiceEnabled,
        hotwordEnabled = hotwordEnabled
    )

    // ── Theme preferences ─────────────────────────────────────────────────────

    var darkMode: String
        get() = themePrefs.getString(KEY_DARK_MODE, "SYSTEM") ?: "SYSTEM"
        set(value) { themePrefs.edit().putString(KEY_DARK_MODE, value).apply() }

    var accentColorHex: String
        get() = themePrefs.getString(KEY_ACCENT_COLOR, "#6C63FF") ?: "#6C63FF"
        set(value) { themePrefs.edit().putString(KEY_ACCENT_COLOR, value).apply() }

    private val themePrefs by lazy {
        context.getSharedPreferences(THEME_PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Global reset ──────────────────────────────────────────────────────────

    /**
     * Reset ALL preference stores to their factory defaults.
     *
     * Called by "Reset to Defaults" in Settings. This covers:
     *   - Execution mode, privacy level, provider, permissions
     *   - Voice pitch, rate, preset
     *   - Theme dark-mode and accent colour
     *
     * UserProfile preferences (display name, persona, etc.) are intentionally
     * excluded — resetting them would require a server-side Firestore delete.
     */
    fun resetAllToDefaults() {
        Log.i(TAG, "AIRI_PROOF PREFS_RESET_ALL_TO_DEFAULTS")

        // ── Execution prefs ───────────────────────────────────────────────────
        execPrefs.executionMode            = ExecutionMode.HYBRID
        execPrefs.privacyLevel             = PrivacyLevel.BALANCED
        execPrefs.preferredProvider        = CloudProvider.OPENAI
        execPrefs.internetPermissionGranted = false
        execPrefs.offlineFallbackEnabled   = true
        execPrefs.maxDailyCloudTokens      = 50_000

        // ── Voice prefs ───────────────────────────────────────────────────────
        val std = VoicePreferencesStore.PersonalityPreset.STANDARD
        VoicePreferencesStore.save(
            context        = context,
            pitch          = std.pitch,
            rate           = std.rate,
            voiceName      = "",
            preset         = std,
            voiceEnabled   = false,
            hotwordEnabled = false
        )

        // ── Theme prefs ───────────────────────────────────────────────────────
        themePrefs.edit()
            .putString(KEY_DARK_MODE,    "SYSTEM")
            .putString(KEY_ACCENT_COLOR, "#6C63FF")
            .apply()

        Log.i(TAG, "AIRI_PROOF PREFS_RESET_COMPLETE stores=exec,voice,theme")
    }

    private companion object {
        const val TAG             = "AIRI_PreferenceCoordinator"
        const val THEME_PREFS_NAME = "airi_theme_prefs"
        const val KEY_DARK_MODE   = "dark_mode"
        const val KEY_ACCENT_COLOR = "accent_color_hex"
    }
}
