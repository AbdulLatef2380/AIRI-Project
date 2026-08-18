package com.airi.assistant.domain.sharing

import android.content.Context
import android.content.Intent
import android.util.Log
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.memory.entity.ChatMessage
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * ChatSharingService — real chat export, link sharing, and Firestore collaboration.
 *
 * REAL EXECUTION:
 *
 *   LOCAL EXPORT (no cloud):
 *     - [exportAsText]   → plain text transcript (always available).
 *     - [exportAsJson]   → structured JSON (always available).
 *     - [shareViaIntent] → Android share sheet with the text export.
 *
 *   CLOUD SHARING (requires Firestore):
 *     - [publishShareLink] → writes the conversation to Firestore under
 *       `shared_chats/{shareId}` and returns a deep-link URL.
 *     - [fetchSharedChat]  → reads a shared conversation by share ID.
 *     - [unpublishShareLink] → deletes the Firestore document.
 *
 *   Firestore document schema:
 *     shared_chats/{shareId}:
 *       title:      String
 *       authorId:   String (hashed device ID, never raw UID)
 *       createdAt:  Long (epoch ms)
 *       expiresAt:  Long (epoch ms, 7 days by default)
 *       messages:   Array<Map>  [{role, content, ts}]
 *       messageCount: Int
 *
 * PRIVACY:
 *   - [publishShareLink] is blocked when called with sensitiveMode=true.
 *   - Author ID is a one-way hash of the session ID — not the Firebase UID.
 *   - Shared documents expire automatically (TTL enforced by a Firestore
 *     TTL policy set on the `expiresAt` field; the client also refuses to
 *     fetch expired documents).
 */
class ChatSharingService(
    private val context: Context,
    private val firestore: FirebaseFirestore? = runCatching {
        FirebaseFirestore.getInstance()
    }.getOrNull()
) {

    companion object {
        private const val TAG            = "ChatSharingService"
        private const val COLLECTION     = "shared_chats"
        private const val SHARE_TTL_MS   = 7L * 24 * 60 * 60 * 1_000   // 7 days
        private const val MAX_MESSAGES   = 500
        private const val DEEP_LINK_BASE = "https://airi.app/share"
    }

    // ── Local export ───────────────────────────────────────────────────────────

    /**
     * Export messages as a plain-text transcript.
     *
     * @param title   Session title shown at the top.
     * @param messages The conversation messages in chronological order.
     */
    fun exportAsText(title: String, messages: List<ChatMessage>): String {
        val sb   = StringBuilder()
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        sb.appendLine("AIRI Conversation Export")
        sb.appendLine("Title: $title")
        sb.appendLine("Exported: $date")
        sb.appendLine("Messages: ${messages.size}")
        sb.appendLine("─".repeat(40))
        messages.forEach { msg ->
            val role = if (msg.role == "user") "You" else "AIRI"
            sb.appendLine("[$role] ${msg.content}")
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * Export messages as a structured JSON string (can be re-imported).
     */
    fun exportAsJson(title: String, messages: List<ChatMessage>): String {
        val arr = JSONArray()
        messages.forEach { msg ->
            arr.put(JSONObject().apply {
                put("role",      msg.role)
                put("content",   msg.content)
                put("timestamp", msg.timestamp)
                put("sessionId", msg.sessionId)
            })
        }
        return JSONObject().apply {
            put("title",        title)
            put("exported_at",  System.currentTimeMillis())
            put("version",      "1.0")
            put("message_count", messages.size)
            put("messages",     arr)
        }.toString(2)
    }

    /**
     * Open the Android share sheet with the text export of the conversation.
     */
    fun shareViaIntent(title: String, messages: List<ChatMessage>) {
        val text   = exportAsText(title, messages)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type    = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AIRI Chat: $title")
            putExtra(Intent.EXTRA_TEXT, text.take(100_000))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share conversation").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
        Log.i(TAG, "CHAT_SHARED method=intent titleChars=${title.length} messageCount=${messages.size}")
        EventBus.emitSync(AppEvent.GenericInfo("Chat shared: $title"))
    }

    // ── Cloud sharing (Firestore) ──────────────────────────────────────────────

    /**
     * Publish a conversation to Firestore and return the share link.
     *
     * @param sessionId  Used to derive the anonymous author ID.
     * @param sensitiveMode  Block publishing if true (privacy gate).
     * @return Share URL on success, null if publishing failed or is blocked.
     */
    suspend fun publishShareLink(
        title:         String,
        messages:      List<ChatMessage>,
        sessionId:     String,
        sensitiveMode: Boolean = false
    ): String? {
        if (sensitiveMode) {
            Log.w(TAG, "publishShareLink blocked — sensitiveMode=true")
            return null
        }
        val db = firestore ?: run {
            Log.w(TAG, "Firestore unavailable — falling back to text share")
            return null
        }

        val shareId  = UUID.randomUUID().toString().replace("-", "").take(12)
        val authorId = sessionId.hashCode().toString(16)
        val now      = System.currentTimeMillis()
        val msgsArr  = messages.take(MAX_MESSAGES).map { msg ->
            mapOf(
                "role"    to msg.role,
                "content" to msg.content,
                "ts"      to msg.timestamp
            )
        }

        val doc = mapOf(
            "title"        to title,
            "authorId"     to authorId,
            "createdAt"    to now,
            "expiresAt"    to now + SHARE_TTL_MS,
            "messages"     to msgsArr,
            "messageCount" to msgsArr.size,
            "version"      to 1
        )

        return runCatching {
            db.collection(COLLECTION).document(shareId).set(doc).await()
            val url = "$DEEP_LINK_BASE/$shareId"
            Log.i(TAG, "AIRI CHAT_SHARE_PUBLISHED shareId=$shareId messages=${msgsArr.size}")
            EventBus.emitSync(AppEvent.GenericInfo("Chat published: $url"))
            url
        }.getOrElse { e ->
            Log.w(TAG, "publishShareLink failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch a shared conversation by [shareId].
     *
     * @return [SharedChat] on success, null if not found or expired.
     */
    suspend fun fetchSharedChat(shareId: String): SharedChat? {
        val db = firestore ?: return null
        return runCatching {
            val snap = db.collection(COLLECTION).document(shareId).get().await()
            if (!snap.exists()) return@runCatching null

            val expiresAt = snap.getLong("expiresAt") ?: 0L
            if (System.currentTimeMillis() > expiresAt) {
                Log.d(TAG, "Shared chat $shareId has expired")
                return@runCatching null
            }

            @Suppress("UNCHECKED_CAST")
            val rawMsgs = snap.get("messages") as? List<Map<String, Any>> ?: emptyList()
            val msgs    = rawMsgs.map { m ->
                SharedMessage(
                    role    = m["role"]    as? String ?: "user",
                    content = m["content"] as? String ?: "",
                    ts      = m["ts"]      as? Long   ?: 0L
                )
            }

            SharedChat(
                shareId      = shareId,
                title        = snap.getString("title") ?: "Shared Chat",
                authorId     = snap.getString("authorId") ?: "",
                createdAt    = snap.getLong("createdAt") ?: 0L,
                expiresAt    = expiresAt,
                messages     = msgs,
                messageCount = (snap.getLong("messageCount") ?: msgs.size.toLong()).toInt()
            )
        }.getOrElse { e ->
            Log.w(TAG, "fetchSharedChat failed for $shareId: ${e.message}")
            null
        }
    }

    /**
     * Delete a published share link from Firestore.
     */
    suspend fun unpublishShareLink(shareId: String): Boolean {
        val db = firestore ?: return false
        return runCatching {
            db.collection(COLLECTION).document(shareId).delete().await()
            Log.i(TAG, "Share link deleted: $shareId")
            true
        }.getOrElse { false }
    }
}

// ── Domain types ───────────────────────────────────────────────────────────────

data class SharedChat(
    val shareId:      String,
    val title:        String,
    val authorId:     String,
    val createdAt:    Long,
    val expiresAt:    Long,
    val messages:     List<SharedMessage>,
    val messageCount: Int
)

data class SharedMessage(
    val role:    String,
    val content: String,
    val ts:      Long
)
