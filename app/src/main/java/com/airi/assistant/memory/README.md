# Memory package

This package owns Room-backed chat history, long-term memory admission, embeddings, and retrieval-augmented context.

## Memory model

Conversation history and durable memory are separate. Recent normal chat rows are bounded per session. `MemoryAdmissionPolicy` decides whether a turn is eligible for embedding and rejects transient, oversized, and sensitive content. Durable extracted facts require an explicit user memory request and are restricted to non-sensitive preference, dislike, language, and project categories.

`EmbeddingService` performs semantic search only inside the current session. `RagRetriever` combines bounded semantic hits with explicit long-term memory and labels all injected content as untrusted historical reference data. It must not be treated as instructions.

## Limits and follow-up

Memory policy detection is heuristic, not a substitute for a complete PII classifier. SQLCipher migration and all Room migrations require real-device validation before a release claim. The build version and implementation details must be checked in `AiriDatabase.kt`, not inferred from historical reports.

## Verification

Static verification covers admission-policy use, session-scoped vector retrieval, and RAG prompt framing. Full Room migration and device-performance tests remain pending.
