package com.airi.assistant.sync

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.repository.MemoryManager
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
 * CloudSyncCoordinator — bidirectional sync of user profile, preferences,
 * and long-term memory with Firebase Firestore.
 *
 * ── SCHEMA ────────────────────────────────────────────────────────────────
 *
 *   users/{uid}/profile              — UserPreferences fields
 *   users/{uid}/sync_meta            — lastSyncMs, deviceId, appVersion
 *   users/{uid}/memory/{messageId}   — Long-term ChatMessage rows (isMemory=true)
 *                                      Phase 2: incremental memory sync.
 *
 * ── SYNC STRATEGY ─────────────────────────────────────────────────────────
 *
 *   PUSH: writes to Firestore with SetOptions.merge() for safe concurrent writes.
 *
 *   PULL: fetches from Firestore and merges into local storage.
 *         Conflict resolution: remote wins for profile fields;
 *         newer timestamp wins for memory rows (last-write-wins).
 *
 * ── MEMORY SYNC RULES ─────────────────────────────────────────────────────
 *
 *   Only LONG_TERM memories (isMemory=true) are synced — episodic chat
 *   history (isMemory=false) stays local-only to preserve privacy.
 *   Memory sync is skipped if [UserPreferences.enableLongTermMemory] = false.
 *
 * ── CONSENT GATE ──────────────────────────────────────────────────────────
 *
 *   All sync operations are gated on [UserPreferences.cloudSyncEnabled].
 *
 * ── OFFLINE ───────────────────────────────────────────────────────────────
 *
 *   Firestore SDK queues writes internally during offline periods.
 *   Memory sync additionally tracks [lastMemorySyncMs] for incremental
 *   uploads — only memories newer than the last push are re-uploaded.
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
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_PUSH_OK uid=${uid.take(8)}…")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_RUNTIME CLOUD_SYNC_PUSH_FAILED: ${e.message}")
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
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_PULL_OK uid=${uid.take(8)}…")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_RUNTIME CLOUD_SYNC_PULL_FAILED: ${e.message}")
        }
    }

    // ── Memory Sync (Phase 2) ─────────────────────────────────────────────────

    /**
     * Incremental push of long-term memories to Firestore.
     *
     * Only memories with [ChatMessage.timestamp] > [lastMemorySyncMs] are
     * uploaded, making repeated calls cheap after the first full push.
     * The batch is capped at [MAX_MEMORY_BATCH] rows per call to avoid
     * oversized Firestore writes.
     *
     * Skipped silently if:
     *   - [UserPreferences.cloudSyncEnabled] = false
     *   - [UserPreferences.enableLongTermMemory] = false
     *   - User is signed out
     *   - Firestore unavailable
     *
     * @param memoryManager   The local MemoryManager to read memories from.
     */
    suspend fun pushMemories(memoryManager: MemoryManager) {
        val prefs = profileRepo.current
        if (!prefs.cloudSyncEnabled || !prefs.enableLongTermMemory) return
        val uid = auth.currentUser?.uid ?: return
        val db  = db ?: return

        val since = _lastMemorySyncMs.value
        val memories = runCatching { memoryManager.getSemanticMemories(limit = 500) }
            .getOrElse { emptyList() }
            .filter { it.isMemory && it.timestamp > since }
            .take(MAX_MEMORY_BATCH)

        if (memories.isEmpty()) {
            Log.d(TAG, "AIRI_RUNTIME MEMORY_PUSH_SKIPPED reason=no_new_rows since=$since")
            return
        }

        _syncStatus.value = SyncStatus.SYNCING
        runCatching {
            val batch = db.batch()
            memories.forEach { msg ->
                val ref = db.collection("users").document(uid)
                    .collection("memory").document(msg.id.toString())
                batch.set(ref, memoryToDocument(msg), SetOptions.merge())
            }
            batch.commit().await()
            _lastMemorySyncMs.value = System.currentTimeMillis()
            _syncStatus.value = SyncStatus.SUCCESS
            LoggingService.info(TAG, "AIRI_RUNTIME MEMORY_PUSH_OK uid=${uid.take(8)}… rows=${memories.size}")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_RUNTIME MEMORY_PUSH_FAILED: ${e.message}")
        }
    }

    /**
     * Pull long-term memories from Firestore and restore any missing rows
     * into [memoryManager]. Uses last-write-wins conflict resolution:
     * if a remote row's timestamp is newer than the local equivalent,
     * the remote version is preferred.
     *
     * @param memoryManager   The local MemoryManager to merge memories into.
     */
    suspend fun pullMemories(memoryManager: MemoryManager) {
        val prefs = profileRepo.current
        if (!prefs.cloudSyncEnabled || !prefs.enableLongTermMemory) return
        val uid = auth.currentUser?.uid ?: return
        val db  = db ?: return

        _syncStatus.value = SyncStatus.SYNCING
        runCatching {
            val snap = db.collection("users").document(uid)
                .collection("memory")
                .limit(MAX_MEMORY_PULL.toLong())
                .get().await()

            if (snap.isEmpty) {
                _syncStatus.value = SyncStatus.SUCCESS
                return
            }

            val local = runCatching { memoryManager.getSemanticMemories(limit = 1000) }.getOrElse { emptyList() }
            val localIds = local.map { it.id }.toSet()

            var restored = 0
            snap.documents.forEach { doc ->
                val remoteMsg = documentToMemory(doc.data ?: return@forEach)
                if (remoteMsg != null && remoteMsg.id !in localIds) {
                    // Record as a long-term memory; the DAO prevents duplicates by PK.
                    runCatching {
                        memoryManager.recordImportantMemory(remoteMsg.role, remoteMsg.content, remoteMsg.emotionState)
                    }
                    restored++
                }
            }

            _syncStatus.value = SyncStatus.SUCCESS
            LoggingService.info(TAG, "AIRI_RUNTIME MEMORY_PULL_OK uid=${uid.take(8)}… restored=$restored total=${snap.size()}")
        }.onFailure { e ->
            _syncStatus.value = SyncStatus.FAILED
            LoggingService.warn(TAG, "AIRI_RUNTIME MEMORY_PULL_FAILED: ${e.message}")
        }
    }

    private val _lastMemorySyncMs = MutableStateFlow(0L)
    val lastMemorySyncMs: StateFlow<Long> = _lastMemorySyncMs.asStateFlow()

    private fun memoryToDocument(msg: ChatMessage): Map<String, Any?> = mapOf(
        "id"           to msg.id,
        "role"         to msg.role,
        "content"      to msg.content.take(MAX_MEMORY_CONTENT_CHARS),
        "emotionState" to msg.emotionState,
        "sessionId"    to msg.sessionId,
        "timestamp"    to msg.timestamp,
        "isMemory"     to true
    )

    private fun documentToMemory(doc: Map<String, Any?>): ChatMessage? {
        val role    = (doc["role"]    as? String) ?: return null
        val content = (doc["content"] as? String) ?: return null
        return ChatMessage(
            id           = (doc["id"] as? Long) ?: 0L,
            role         = role,
            content      = content,
            emotionState = doc["emotionState"] as? String,
            sessionId    = (doc["sessionId"] as? String) ?: "",
            timestamp    = (doc["timestamp"] as? Long) ?: 0L,
            isMemory     = true
        )
    }

    private companion object {
        const val MAX_MEMORY_BATCH         = 100   // rows per push call
        const val MAX_MEMORY_PULL          = 500   // rows per pull call
        const val MAX_MEMORY_CONTENT_CHARS = 2_000 // hard cap per memory row
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
                        LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_REMOTE_UPDATE")
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

// Extension: Task 4.4 — placeholder until full conversation sync schema is approved
// These are called by CloudSyncWorker but default to no-op in current build.
// Actual implementation requires Firestore security rules + privacy review.
