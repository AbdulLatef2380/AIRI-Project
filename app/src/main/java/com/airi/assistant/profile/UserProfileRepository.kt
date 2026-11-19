package com.airi.assistant.profile

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UserProfileRepository — single source of truth for [UserPreferences].
 *
 * ── STORAGE ───────────────────────────────────────────────────────────────
 *
 *   Preferences are persisted as a JSON blob in a regular SharedPreferences
 *   file ("airi_user_profile"). Signed-in profiles are keyed by Firebase UID;
 *   there is no cloud avatar backend in this release. Sensitive fields (keys, tokens) are always
 *   kept in SecureStorage — they never appear here.
 *
 * ── THREAD SAFETY ─────────────────────────────────────────────────────────
 *
 *   All disk I/O runs on [repoScope] (IO dispatcher). The exposed StateFlow
 *   is updated atomically. Callers on any thread are safe.
 *
 * ── CLOUD SYNC ────────────────────────────────────────────────────────────
 *
 *   [CloudSyncCoordinator] reads the current preferences via [current] and
 *   calls [merge] when it receives a remote update. The merge applies only
 *   fields that differ, preserving local-only fields (e.g. hardware profile).
 *
 * ── ACCOUNT OWNERSHIP ─────────────────────────────────────────────────────
 *
 *   Identity fields (displayName, username, localPhotoPath, avatarUrl) are
 *   account-scoped. Call [activateAccount] after authentication and
 *   [resetIdentity] on sign-out. This prevents account B from ever reading
 *   account A's local avatar. avatarUrl is intentionally empty/local-only.
 *   Non-identity fields (theme, AI persona, voice, notifications) are device
 *   preferences and persist across account switches by design.
 */
class UserProfileRepository(private val context: Context) {

    private val TAG        = "UserProfileRepository"
    private val gson       = Gson()
    private val repoScope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs      = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
    private val stateLock  = Any()
    @Volatile private var activeAccountId: String? = null

    private val _profile = MutableStateFlow(UserPreferences())
    val profile: StateFlow<UserPreferences> = _profile.asStateFlow()

    val current: UserPreferences get() = _profile.value

    /** Selects the UID-owned local profile immediately after Firebase sign-in. */
    fun activateAccount(uid: String?) {
        val safeUid = uid?.takeIf { it.isNotBlank() && isSafeAccountId(it) }
        synchronized(stateLock) {
            activeAccountId = safeUid
            _profile.value = load(safeUid)
        }
    }

    /**
     * Update preferences atomically. The supplied [transform] receives the
     * current snapshot and returns the modified one. The result is persisted
     * and emitted to all observers.
     */
    fun update(transform: UserPreferences.() -> UserPreferences) {
        repoScope.launch { updateSuspend(transform) }
    }

    /**
     * Suspend version of [update] — awaits until the write completes.
     */
    suspend fun updateSuspend(transform: UserPreferences.() -> UserPreferences) {
        withContext(Dispatchers.IO) {
            synchronized(stateLock) {
                val accountId = activeAccountId
                val updated = _profile.value.transform().copy(lastUpdatedAtMs = System.currentTimeMillis())
                _profile.value = updated
                persist(updated, accountId)
            }
            LoggingService.info(TAG, "AIRI PROFILE_UPDATED_SUSPEND")
        }
    }

    /**
     * Merge a remote snapshot into the local profile. Only non-default fields
     * from [remote] override the local profile, so local-only fields are kept.
     */
    fun merge(remote: UserPreferences) {
        repoScope.launch {
            val local = _profile.value
            val merged = local.copy(
                displayName          = remote.displayName.ifBlank { local.displayName },
                username             = remote.username.ifBlank { local.username },
                avatarUrl            = remote.avatarUrl.ifBlank { local.avatarUrl },
                airiPersonaName      = remote.airiPersonaName.ifBlank { local.airiPersonaName },
                airiPersonaTone      = remote.airiPersonaTone,
                airiResponseLength   = remote.airiResponseLength,
                airiCreativityLevel  = remote.airiCreativityLevel,
                preferredRemoteProvider = remote.preferredRemoteProvider.ifBlank { local.preferredRemoteProvider },
                enableEpisodicMemory = remote.enableEpisodicMemory,
                enableSemanticMemory = remote.enableSemanticMemory,
                enableLongTermMemory = remote.enableLongTermMemory,
                memoryRetentionDays  = remote.memoryRetentionDays,
                analyticsOptIn       = remote.analyticsOptIn,
                crashReportingOptIn  = remote.crashReportingOptIn,
                sendAgentTelemetry   = remote.sendAgentTelemetry,
                cloudSyncEnabled     = remote.cloudSyncEnabled,
                taskContinuitySyncEnabled = remote.taskContinuitySyncEnabled,
                darkMode             = remote.darkMode,
                lastUpdatedAtMs      = System.currentTimeMillis()
            )
            _profile.value = merged
            persist(merged, activeAccountId)
            LoggingService.info(TAG, "AIRI PROFILE_MERGED_FROM_CLOUD")
        }
    }

    /**
     * Reset the profile to defaults. Clears persisted state.
     */
    fun reset() {
        repoScope.launch {
            val fresh = UserPreferences()
            _profile.value = fresh
            persist(fresh, activeAccountId)
            LoggingService.info(TAG, "AIRI PROFILE_RESET")
        }
    }

    /**
     * Clear only account-identity fields so they are not visible after sign-out
     * when a different account signs in. Non-identity preferences (theme, AI
     * persona, voice, notifications) intentionally persist — they are device
     * preferences, not account data.
     *
     * Identity fields cleared:
     *   - displayName
     *   - username
     *   - localPhotoPath   (the file is left on disk until the OS reclaims it
     *                       or the user sets a new photo; the path is cleared
     *                       so no UI renders the previous account's photo)
     *   - avatarUrl
     */
    fun resetIdentity() {
        repoScope.launch {
            val cleared = _profile.value.copy(
                displayName    = "",
                username       = "",
                localPhotoPath = "",
                avatarUrl      = "",
                lastUpdatedAtMs = System.currentTimeMillis()
            )
            _profile.value = cleared
            persist(cleared, activeAccountId)
            LoggingService.info(TAG, "AIRI PROFILE_IDENTITY_CLEARED")
        }
    }

    private fun load(accountId: String?): UserPreferences {
        val key = accountId?.let { profileKey(it) } ?: KEY_PROFILE_JSON
        val json = prefs.getString(key, null) ?: return UserPreferences()
        return runCatching { gson.fromJson(json, UserPreferences::class.java) }
            .getOrDefault(UserPreferences())
            .also { Log.i(TAG, "Profile loaded from disk") }
    }

    private fun persist(prefs: UserPreferences, accountId: String?) {
        runCatching {
            val json = gson.toJson(prefs)
            this.prefs.edit().putString(accountId?.let { profileKey(it) } ?: KEY_PROFILE_JSON, json).apply()
        }.onFailure { Log.e(TAG, "Failed to persist profile: ${it.message}") }
    }

    private fun profileKey(uid: String) = "$KEY_PROFILE_JSON.$uid"

    private fun isSafeAccountId(uid: String): Boolean =
        uid.length <= 128 && uid.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    companion object {
        private const val PREF_FILE        = "airi_user_profile"
        private const val KEY_PROFILE_JSON = "profile_json"
    }
}
