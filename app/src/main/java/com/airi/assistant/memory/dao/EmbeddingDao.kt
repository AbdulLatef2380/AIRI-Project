package com.airi.assistant.memory.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.memory.entity.MessageEmbedding

/**
 * Persistence for semantic-memory vectors. Kept tiny on purpose: the
 * vector math (cosine similarity, top-k) lives in
 * [com.airi.assistant.memory.embedding.EmbeddingService] so this layer
 * only handles I/O.
 *
 * Top-k is implemented as `getAllForSession()` + an in-memory scan rather
 * than a clever SQL trick, because (a) we cannot run dot-products inside
 * SQLite without a custom function, and (b) at AIRI's per-session message
 * cap (200) the scan is sub-millisecond.
 */
@Dao
interface EmbeddingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: MessageEmbedding)

    @Query("SELECT * FROM message_embedding WHERE sessionId = :sessionId AND dim = :dim")
    suspend fun getAllForSession(sessionId: String, dim: Int): List<MessageEmbedding>

    @Query("SELECT * FROM message_embedding WHERE messageId = :messageId LIMIT 1")
    suspend fun getByMessage(messageId: Long): MessageEmbedding?

    @Query("DELETE FROM message_embedding WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: Long)

    /** Used by the Memory screen's wipe-all action. */
    @Query("DELETE FROM message_embedding")
    suspend fun deleteAll()

    /** Bulk hydrate the underlying messages for the top-k IDs we picked. */
    @Query("SELECT * FROM episodic_memory WHERE id IN (:ids)")
    suspend fun loadMessagesByIds(ids: List<Long>): List<ChatMessage>

    @Query("SELECT COUNT(*) FROM message_embedding")
    suspend fun count(): Int
}
