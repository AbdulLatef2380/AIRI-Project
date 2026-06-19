package com.airi.assistant.ai.skills

import android.util.Log
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.repository.MemoryManager

/**
 * SkillMemoryBridge — typed API for skills to interact with the memory layer.
 *
 * Before granting access the bridge enforces the skill's declared
 * [SkillMemoryAccess] permission level. Write attempts by a skill with only
 * READ_ONLY access return an error result instead of persisting anything.
 */
class SkillMemoryBridge(
    private val manager:     MemoryManager,
    private val access:      SkillMemoryAccess,
    private val sessionId:   String,
    private val skillId:     String
) {
    companion object {
        private const val TAG = "SkillMemoryBridge"

        /**
         * Create a bridge. Returns null when [access] is NONE — skills with
         * no memory permission should never receive a bridge instance.
         */
        fun create(
            manager:   MemoryManager?,
            access:    SkillMemoryAccess,
            sessionId: String,
            skillId:   String
        ): SkillMemoryBridge? {
            if (access == SkillMemoryAccess.NONE || manager == null) return null
            return SkillMemoryBridge(manager, access, sessionId, skillId)
        }
    }

    // ── Read operations ───────────────────────────────────────────────────────

    /**
     * Retrieve the most recent [limit] messages from the active session.
     */
    suspend fun getRecentMessages(limit: Int = 10): List<ChatMessage> {
        if (!access.canRead) {
            Log.w(TAG, "[$skillId] READ denied — access=$access")
            return emptyList()
        }
        return try {
            manager.getRecentMessages(limit)
        } catch (e: Exception) {
            Log.e(TAG, "[$skillId] getRecentMessages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Perform a semantic / recent-message search for [query].
     * Falls back to recent messages when the embedding model is not loaded.
     */
    suspend fun search(query: String, k: Int = 5): List<ChatMessage> {
        if (!access.canRead) {
            Log.w(TAG, "[$skillId] SEARCH denied — access=$access")
            return emptyList()
        }
        return try {
            if (manager.isSemanticMemoryReady() && sessionId.isNotEmpty()) {
                manager.semanticSearch(sessionId, query, k).map { it.message }
            } else {
                manager.getRecentMessages(k)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$skillId] search failed: ${e.message}")
            emptyList()
        }
    }

    // ── Write operations ──────────────────────────────────────────────────────

    /**
     * Record a fact or skill output into long-term memory.
     *
     * @param role    "skill", "assistant", or "user"
     * @param content The text to persist.
     */
    fun record(role: String = "skill", content: String) {
        if (!access.canWrite) {
            Log.w(TAG, "[$skillId] WRITE denied — access=$access")
            return
        }
        try {
            manager.recordImportantMemory(role, "[$skillId] $content")
        } catch (e: Exception) {
            Log.e(TAG, "[$skillId] record failed: ${e.message}")
        }
    }

    /**
     * Record an interaction from a skill execution into the session history.
     */
    suspend fun recordInteraction(
        role:    String = "skill",
        content: String
    ) {
        if (!access.canWrite) {
            Log.w(TAG, "[$skillId] WRITE denied — access=$access")
            return
        }
        try {
            manager.recordChatMessage(sessionId, role, "[$skillId] $content")
        } catch (e: Exception) {
            Log.e(TAG, "[$skillId] recordInteraction failed: ${e.message}")
        }
    }

    // ── Convenience ───────────────────────────────────────────────────────────

    /**
     * Format recent memory as a readable context block for inclusion in a skill's
     * model prompt, prefixed with a skills-aware header.
     */
    suspend fun buildContextBlock(query: String = "", limit: Int = 5): String {
        val messages = if (query.isBlank()) getRecentMessages(limit) else search(query, limit)
        if (messages.isEmpty()) return ""
        return buildString {
            append("\n[Memory context for skill $skillId]:\n")
            messages.forEach { msg ->
                append("${msg.role}: ${msg.content.take(200)}\n")
            }
        }
    }
}
