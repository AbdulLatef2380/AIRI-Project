package com.airi.assistant.memory

/**
 * Formal enumeration of AIRI's memory layers.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LAYER DEFINITIONS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   SHORT_TERM   — Active context window tokens flowing through the LLM.
 *                  Volatile: cleared on session end. Capacity: model ctx limit.
 *                  Owner: LlamaManager (kv-cache) / RemoteModelExecutor.
 *
 *   WORKING      — Session-level structured facts extracted from the
 *                  current conversation by MemoryExtractor. Persists for
 *                  the session duration only. Injected into prompts.
 *                  Owner: PromptCompressor / ConversationSummarizer.
 *
 *   LONG_TERM    — Persistent user preferences, known facts, and
 *                  important memories flagged as isMemory=true in the DB.
 *                  Survives sessions. Never auto-expires.
 *                  Owner: MemoryManager (Room DB, isMemory=1 rows).
 *
 *   SEMANTIC     — Vector-embedded memories for similarity search (RAG).
 *                  Retrieved by semantic query at prompt assembly time.
 *                  Owner: EmbeddingService + MemoryDao (embedding table).
 *
 *   EPISODIC     — Raw chat history. Recent N turns (sliding window).
 *                  Auto-pruned by MemoryManager.pruneOldSessionMessages.
 *                  Owner: MemoryManager (Room DB, isMemory=0 rows).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PRIVACY CONSTRAINTS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   SHORT_TERM   — Never persisted. Never uploaded without user consent.
 *   WORKING      — Never persisted to disk. Session-scoped in-memory only.
 *   LONG_TERM    — Persisted locally. Cloud exclusion if privacyLevel=MAX.
 *   SEMANTIC     — Persisted locally. Embedding model runs on-device.
 *   EPISODIC     — Persisted locally. Cloud exclusion if privacyLevel=MAX.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * VISIBILITY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Users can view all layers in MemoryScreen.
 *   Users can delete any layer via MemoryScreen or PrivacyDataSettingsScreen.
 *   All deletions propagate synchronously to the backing store.
 */
enum class MemoryLayer(
    val displayName:   String,
    val description:   String,
    val isVolatile:    Boolean,
    val isPersisted:   Boolean,
    val cloudEligible: Boolean
) {
    SHORT_TERM(
        displayName   = "Active Context",
        description   = "Tokens currently in the model's context window (kv-cache). Cleared after every turn.",
        isVolatile    = true,
        isPersisted   = false,
        cloudEligible = false
    ),

    WORKING(
        displayName   = "Working Memory",
        description   = "Structured facts extracted from this session. Cleared when the session ends.",
        isVolatile    = true,
        isPersisted   = false,
        cloudEligible = false
    ),

    EPISODIC(
        displayName   = "Conversation History",
        description   = "Recent chat messages in the sliding window. Auto-pruned.",
        isVolatile    = false,
        isPersisted   = true,
        cloudEligible = true
    ),

    LONG_TERM(
        displayName   = "Long-term Memory",
        description   = "Persistent user preferences and important remembered facts.",
        isVolatile    = false,
        isPersisted   = true,
        cloudEligible = true
    ),

    SEMANTIC(
        displayName   = "Semantic Memory",
        description   = "Vector-embedded memories for similarity search (RAG). On-device embeddings.",
        isVolatile    = false,
        isPersisted   = true,
        cloudEligible = false
    )
}

/**
 * A memory item tagged with its [MemoryLayer].
 *
 * Used by [AgentObservabilityHub] and MemoryScreen to display the layered
 * memory model without exposing internal DB structure to the UI layer.
 */
data class LayeredMemoryItem(
    val layer:       MemoryLayer,
    val id:          String,
    val content:     String,
    val timestampMs: Long,
    val sessionId:   String = "",
    val importance:  Float  = 0.5f,   // 0.0–1.0
    val canDelete:   Boolean = true
)
