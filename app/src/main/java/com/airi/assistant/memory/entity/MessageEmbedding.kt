package com.airi.assistant.memory.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted L2-normalized embedding vector for one [ChatMessage].
 *
 * Storage choice: float[] is serialized to a `ByteArray` (4 bytes per dim,
 * little-endian) so we don't need a TypeConverter. Cosine similarity over
 * L2-normalized vectors degenerates to a dot product, which makes
 * semantic search a single linear pass with zero allocations beyond the
 * candidate vectors. For the message volumes AIRI handles (≤ a few
 * thousand rows), a brute-force scan is faster than maintaining an HNSW
 * index in SQLite and avoids a native dependency.
 *
 * `messageId` references `episodic_memory.id` with CASCADE delete so a
 * pruned message also drops its embedding (no orphan vectors).
 *
 * `dim` is denormalised onto every row so the search loop can validate
 * dimensional compatibility without a join — different embedding models
 * produce different-dimension vectors and mixing them would silently
 * return garbage.
 */
@Entity(
    tableName = "message_embedding",
    foreignKeys = [
        ForeignKey(
            entity         = ChatMessage::class,
            parentColumns  = ["id"],
            childColumns   = ["messageId"],
            onDelete       = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["messageId"], unique = true),
        Index(value = ["sessionId"]),
        Index(value = ["dim"])
    ]
)
data class MessageEmbedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: Long,
    val sessionId: String,
    val role: String,
    val dim: Int,
    /** Little-endian float32 vector. Length MUST equal `dim * 4`. */
    val vector: ByteArray,
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Required because `ByteArray` uses identity equals by default. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MessageEmbedding) return false
        return id == other.id &&
            messageId == other.messageId &&
            dim == other.dim &&
            vector.contentEquals(other.vector)
    }
    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + messageId.hashCode()
        h = 31 * h + dim
        h = 31 * h + vector.contentHashCode()
        return h
    }
}
