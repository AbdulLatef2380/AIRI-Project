# AIRI Memory Fabric Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for governed long-term admission, scoped retrieval, provenance, user-visible explanation, privacy/expiry gates, deterministic cross-source ranking/deduplication, and Room migration v8. `PARTIAL` for knowledge-source extraction, ranking decay, contradiction handling, taxonomy, and device-level migration verification.

AIRI separates ordinary conversation context from durable memory. A message is not a long-term memory merely because it passed through the model. The persistence path uses one `ChatMessage` table with explicit metadata, so legacy data remains readable while new long-term rows carry ownership and explanation.

| Concern | Runtime contract |
|---|---|
| Admission | Long-term storage requires an explicit user request and must pass `MemoryAdmissionPolicy` sensitivity checks. |
| Ownership | `SESSION`, `PROJECT`, and `USER` scopes determine retrieval; a project memory is never returned for a different project ID. |
| Privacy | Retrieval accepts only rows whose `privacyLevel` is at most the calling context’s approved level. |
| Expiry | A memory with an elapsed `expiresAtMs` is excluded from retrieval. |
| Provenance | Each long-term row stores a bounded, sanitised explanation such as `Explicit request through Memory Agent` or `Extracted from an explicit user memory request`. |
| Explainability | `explainMemory` returns source, provenance, scope, project ID, confidence, importance, privacy, and expiry. MemoryScreen shows why a long-term memory was saved. |
| Deletion and correction | `forgetMemory` deletes a long-term row explicitly. `editMemoryContent` first rejects every non-long-term row, then edits only content after admission validation while preserving provenance, scope, project, privacy, importance, and expiry from the existing durable row. `ChatViewModel` refreshes the visible projection/count after delete or edit; MemoryScreen exposes edit and delete only for long-term rows. Scope/privacy/provenance remain explanation data, not editable fields. |
| Retrieval evidence | `RagRetriever` creates stable citation IDs (`memory-{id}` / `message-{id}`) and carries source, provenance, scope, confidence, and record ID with every retrieved passage. After scope/privacy/expiry/prompt-safety filtering, `RagRetrievalRanker` orders semantic and project-knowledge candidates by their bounded retrieval scores, then appends durable memories in their already-governed DAO order because durable confidence is not query relevance. It removes exact normalized-content duplicates while retaining the first passage in that defined ordering. |

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

A chat request identifies the active workspace project and the effective privacy mode before calling RAG. `RagRetriever` retrieves only long-term rows and semantic context visible to that `(sessionId, projectId, privacyLevel)` tuple. It applies prompt-safety filters first, orders semantic and project-knowledge candidates by their bounded retrieval scores, then appends governed long-term memory in DAO order and removes exact normalized-content duplicates. The ranker neither admits additional content nor rewrites it, changes scope/privacy, treats durable confidence as query relevance, infers truth, or resolves contradictions. It appends citation IDs to the injected context and treats every retrieved passage as untrusted historical data. Vision messages are also stored with the active project ID.

## Evidence

| Evidence | Contract proven |
|---|---|
| `MemoryMetadataPolicyTest` | Scope fallback, privacy/importance bounds, and sensitive provenance sanitisation. |
| `MemoryAdmissionPolicyTest` | Explicit-request and sensitive-content admission rules. |
| `RagQueryPolicyTest` | RAG input and limit bounds. |
| `RagRetrievalRankerTest` | Semantic/project-knowledge score ordering, durable-memory fallback order, exact normalized-content deduplication, and explicit limit handling in JVM. |
| Kotlin compilation and selected unit suite | Room v8 entity/DAO/migration, MemoryAgent, RagRetriever, ChatViewModel, and MemoryScreen compile together, including individual long-term-memory deletion and its localized confirmation flow. |
| `MemoryDaoInsertIdTest.editingLongTermMemoryChangesContentAndPreservesScopedMetadata` | Room fixture updates a long-term row while preserving project/scope/privacy/importance/expiry/provenance, and proves the DAO rejects an ordinary chat row. It is `BUILD_VERIFIED` until instrumentation runs on a device. |

The next knowledge phase must connect an explicit Project File index request to a real extractor/indexer, preserve source chunks and confidence, and expose those citations in the same evidence model. Importing a file must not become implicit long-term memory or knowledge. Physical-device verification remains required for the delete/edit dialogs, TalkBack labels, font-scale rendering, validation failures, and persistence refresh after process recreation. Memory taxonomy expansion, ranking decay, contradiction presentation/resolution, and user export remain separate gaps; no export surface is claimed by this contract.
