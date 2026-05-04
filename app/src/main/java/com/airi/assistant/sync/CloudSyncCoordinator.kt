package com.airi.assistant.sync

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.profile.UserPreferences
import com.airi.assistant.profile.UserProfileRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * CloudSyncCoordinator — bidirectional sync of user profile and preferences
 * with Firebase Firestore.
 *
 * ── SCHEMA ────────────────────────────────────────────────────────────────
 *
 *   users/{uid}/profile    — UserPreferences fields (no PII beyond displayName)
 *   users/{uid}/sync_meta  — lastSyncMs, deviceId, appVersion
 *
 * ── SYNC STRATEGY ─────────────────────────────────────────────────────────
 *
 *   PUSH: called after local profile updates. Writes to Firestore with
 *         SetOptions.merge() so concurrent device writes don't clobber.
 *
 *   PULL: called on app start and on Firestore snapshot listener events.
 *         The remote document is merged into the local profile via
 *         [UserProfileRepository.merge].
 *
 * ── CONSENT GATE ──────────────────────────────────────────────────────────
 *
 *   CloudSyncCoordinator only operates when [UserPreferences.cloudSyncEnabled]
 *   is true. It checks this flag before every push and pull operation.
 *
 * ── OFFLINE ───────────────────────────────────────────────────────────────
 *
 *   Firestore SDK handles offline writes via its internal queue. The
 *   coordinator does not implement its own offline queue.
 */
class CloudSyncCoordinator(
    private val profileRepo: UserProfileRepository
) {

    private val TAG   = "CloudSyncCoordinator"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auth  = FirebaseAuth.getInstance()

    private val db: FirebaseFirestore? = runCatching {
        FirebaseFirestore.getInstance()
    }.getOrNull()

    enum class SyncStatus { IDLE, SYNCING, SUCCESS, FAILED, DISABLED }

    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val _lastSyncMs = MutableStateFlow(0L)
    val lastSyncMs: StateFlow<Long> = _lastSyncMs.asStateFlow()

    init {
        observeRemote()
        scope.launch {
            profileRepo.profile.collect { prefs ->
                if (prefs.cloudSyncEnabled) {
                    push(prefs)
                }
            }
        }
    }

    /**
     * Push the current local profile to Firestore.
     * No-op if sync is disabled, user is signed out, or Firestore unavailable.
     */
    suspend fun push(prefs: UserPreferences = profileRepo.current) {
        if (!prefs.cloudSyncEnabled) {
            _syncStatus.value = SyncStatus.DISABLED
            return
        }
        val uid = auth.currentUser?.uid ?: return
        val db  = db ?: return

        _syncStatus.value = SyncStatus.SYNCING

        runCatching {
            val doc = prefsToDocument(prefs)
            db.collection("users").document(uid)
                .collection("profile").document("preferences")
                .set(doc, SetOptions.merge())
                .await()

            db.collection("users").document(uid)
                .collection("sync_meta").document("device")
                .set(mapOf(
                    "lastSyncMs" to System.currentTimeMillis(),
                    "appVersion" to "1.0"
                ), SetOptions.merge())
                .await()

            _lastSyncMs.value = System.currentTimeMillis()
            _syncStatus.value = SyncStatus.SUCCESS
            LoggingService.info(TAG, "AIRI_PROOF CLOUD_SYNC_PUSH_OK uid=${uid.take(8)}…")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_PROOF CLOUD_SYNC_PUSH_FAILED: ${e.message}")
        }
    }

    /**
     * Pull remote profile and merge into local.
     */
    suspend fun pull() {
        val prefs = profileRepo.current
        if (!prefs.cloudSyncEnabled) return
        val uid = auth.currentUser?.uid ?: return
        val db  = db ?: return

        _syncStatus.value = SyncStatus.SYNCING

        runCatching {
            val snap = db.collection("users").document(uid)
                .collection("profile").document("preferences")
                .get().await()

            if (!snap.exists()) {
                _syncStatus.value = SyncStatus.SUCCESS
                return
            }

            val remote = documentToPrefs(snap.data ?: emptyMap(), prefs)
            profileRepo.merge(remote)
            _lastSyncMs.value = System.currentTimeMillis()
            _syncStatus.value  = SyncStatus.SUCCESS
            LoggingService.info(TAG, "AIRI_PROOF CLOUD_SYNC_PULL_OK uid=${uid.take(8)}…")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_PROOF CLOUD_SYNC_PULL_FAILED: ${e.message}")
        }
    }

    private fun observeRemote() {
        auth.addAuthStateListener { fa ->
            val uid = fa.currentUser?.uid ?: return@addAuthStateListener
            val db  = db ?: return@addAuthStateListener
            if (!profileRepo.current.cloudSyncEnabled) return@addAuthStateListener

            db.collection("users").document(uid)
                .collection("profile").document("preferences")
                .addSnapshotListener { snap, error ->
                    if (error != null || snap == null || !snap.exists()) return@addSnapshotListener
                    scope.launch {
                        val remote = documentToPrefs(snap.data ?: emptyMap(), profileRepo.current)
                        profileRepo.merge(remote)
                        LoggingService.info(TAG, "AIRI_PROOF CLOUD_SYNC_REMOTE_UPDATE")
                    }
                }
        }
    }

    private fun prefsToDocument(p: UserPreferences): Map<String, Any?> = mapOf(
        "displayName"           to p.displayName,
        "avatarUrl"             to p.avatarUrl,
        "preferredLanguage"     to p.preferredLanguage,
        "airiPersonaName"       to p.airiPersonaName,
        "airiPersonaTone"       to p.airiPersonaTone.name,
        "airiResponseLength"    to p.airiResponseLength.name,
        "airiCreativityLevel"   to p.airiCreativityLevel,
        "preferredRemoteProvider" to p.preferredRemoteProvider,
        "preferLocalModel"      to p.preferLocalModel,
        "enableEpisodicMemory"  to p.enableEpisodicMemory,
        "enableSemanticMemory"  to p.enableSemanticMemory,
        "enableLongTermMemory"  to p.enableLongTermMemory,
        "memoryRetentionDays"   to p.memoryRetentionDays,
        "analyticsOptIn"        to p.analyticsOptIn,
        "crashReportingOptIn"   to p.crashReportingOptIn,
        "sendAgentTelemetry"    to p.sendAgentTelemetry,
        "cloudSyncEnabled"      to p.cloudSyncEnabled,
        "darkMode"              to p.darkMode.name,
        "lastUpdatedAtMs"       to p.lastUpdatedAtMs
    )

    private fun documentToPrefs(doc: Map<String, Any?>, base: UserPreferences): UserPreferences {
        fun str(key: String, default: String) = (doc[key] as? String)?.ifBlank { default } ?: default
        fun bool(key: String, default: Boolean) = (doc[key] as? Boolean) ?: default
        fun int(key: String, default: Int) = (doc[key] as? Long)?.toInt() ?: default
        fun float(key: String, default: Float) = (doc[key] as? Double)?.toFloat() ?: default

        return base.copy(
            displayName              = str("displayName", base.displayName),
            avatarUrl                = str("avatarUrl", base.avatarUrl),
            preferredLanguage        = str("preferredLanguage", base.preferredLanguage),
            airiPersonaName          = str("airiPersonaName", base.airiPersonaName),
            airiPersonaTone          = runCatching { UserPreferences.Tone.valueOf(str("airiPersonaTone", base.airiPersonaTone.name)) }.getOrDefault(base.airiPersonaTone),
            airiResponseLength       = runCatching { UserPreferences.Length.valueOf(str("airiResponseLength", base.airiResponseLength.name)) }.getOrDefault(base.airiResponseLength),
            airiCreativityLevel      = float("airiCreativityLevel", base.airiCreativityLevel),
            preferredRemoteProvider  = str("preferredRemoteProvider", base.preferredRemoteProvider),
            preferLocalModel         = bool("preferLocalModel", base.preferLocalModel),
            enableEpisodicMemory     = bool("enableEpisodicMemory", base.enableEpisodicMemory),
            enableSemanticMemory     = bool("enableSemanticMemory", base.enableSemanticMemory),
            enableLongTermMemory     = bool("enableLongTermMemory", base.enableLongTermMemory),
            memoryRetentionDays      = int("memoryRetentionDays", base.memoryRetentionDays),
            analyticsOptIn           = bool("analyticsOptIn", base.analyticsOptIn),
            crashReportingOptIn      = bool("crashReportingOptIn", base.crashReportingOptIn),
            sendAgentTelemetry       = bool("sendAgentTelemetry", base.sendAgentTelemetry),
            cloudSyncEnabled         = bool("cloudSyncEnabled", base.cloudSyncEnabled),
            darkMode                 = runCatching { UserPreferences.DarkMode.valueOf(str("darkMode", base.darkMode.name)) }.getOrDefault(base.darkMode),
            lastUpdatedAtMs          = (doc["lastUpdatedAtMs"] as? Long) ?: base.lastUpdatedAtMs
        )
    }
}
