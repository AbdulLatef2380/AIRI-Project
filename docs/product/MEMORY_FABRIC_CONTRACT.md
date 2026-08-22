# AIRI Memory Fabric Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for governed long-term admission, scoped retrieval, provenance, user-visible explanation, privacy/expiry gates, and Room migration v8. `PARTIAL` for knowledge-source extraction, semantic reranking, and device-level migration verification.

AIRI separates ordinary conversation context from durable memory. A message is not a long-term memory merely because it passed through the model. The persistence path uses one `ChatMessage` table with explicit metadata, so legacy data remains readable while new long-term rows carry ownership and explanation.

| Concern | Runtime contract |
|---|---|
| Admission | Long-term storage requires an explicit user request and must pass `MemoryAdmissionPolicy` sensitivity checks. |
| Ownership | `SESSION`, `PROJECT`, and `USER` scopes determine retrieval; a project memory is never returned for a different project ID. |
| Privacy | Retrieval accepts only rows whose `privacyLevel` is at most the calling context’s approved level. |
| Expiry | A memory with an elapsed `expiresAtMs` is excluded from retrieval. |
| Provenance | Each long-term row stores a bounded, sanitised explanation such as `Explicit request through Memory Agent` or `Extracted from an explicit user memory request`. |
| Explainability | `explainMemory` returns source, provenance, scope, project ID, confidence, importance, privacy, and expiry. MemoryScreen shows why a long-term memory was saved. |
| Deletion and correction | `forgetMemory` deletes a long-term row explicitly; `editMemory` changes content and governance metadata in one SQL update after validation. |
| Retrieval evidence | `RagRetriever` creates stable citation IDs (`memory-{id}` / `message-{id}`) and carries source, provenance, scope, confidence, and record ID with every retrieved passage. |

## Persistence migration

Room v8 adds `projectId`, `memorySource`, `provenance`, `confidence`, `importance`, `memoryScope`, `privacyLevel`, `expiresAtMs`, `lastAccessedAtMs`, and `updatedAtMs` to `episodic_memory`. The `7→8` migration uses only additive columns with defaults and adds project-scope and expiry indexes. Existing records retain `SESSION` scope and remain readable.

## Live request flow

```text
Explicit user memory request
  → MemoryAgent / MemoryManager.storeExplicitMemory
  → sensitivity + metadata policy
  → duplicate check in session
  → Room v8 durable row
  → bounded retention pruning
  → success, duplicate, or rejection result returned to the caller
```

The Memory Agent now waits for this result. It no longer reports that a memory was stored when the previous fire-and-forget compatibility API rejected or silently ignored the write.

## Retrieval flow

A chat request identifies the active workspace project and the effective privacy mode before calling RAG. `RagRetriever` retrieves only long-term rows and semantic context visible to that `(sessionId, projectId, privacyLevel)` tuple. It appends citation IDs to the injected context and treats every retrieved passage as untrusted historical data. Vision messages are also stored with the active project ID.

## Evidence

| Evidence | Contract proven |
|---|---|
| `MemoryMetadataPolicyTest` | Scope fallback, privacy/importance bounds, and sensitive provenance sanitisation. |
| `MemoryAdmissionPolicyTest` | Explicit-request and sensitive-content admission rules. |
| `RagQueryPolicyTest` | RAG input and limit bounds. |
| Kotlin compilation and selected unit suite | Room v8 entity/DAO/migration, MemoryAgent, RagRetriever, ChatViewModel, and MemoryScreen compile together. |

The next knowledge phase must connect an explicit Project File index request to a real extractor/indexer, preserve source chunks and confidence, and expose those citations in the same evidence model. Importing a file must not become implicit long-term memory or knowledge.
