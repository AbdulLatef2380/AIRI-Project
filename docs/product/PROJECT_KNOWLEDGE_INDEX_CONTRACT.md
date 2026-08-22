# AIRI Project Knowledge Index Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for explicit local indexing of extracted Project File text, project isolation, bounded chunking, persistent local lexical retrieval, RAG citations, and visible user initiation. `PARTIAL` for semantic embeddings, reranking, broader document parsers, and device-level import/index verification.

A Project File does not become knowledge when it is imported. The user must explicitly select **Add to knowledge** in the Files screen. This transition is visible in the file’s `IndexState` and is refused for non-text files or resources whose text extraction failed.

## Resource boundary

| Stage | Source of truth | What happens | What never happens implicitly |
|---|---|---|---|
| Import | `ProjectFileManager` | Private managed copy, SHA-256, metadata, bounded preview | RAG or long-term memory ingestion |
| Explicit index request | `ProjectKnowledgeManager.indexProjectFile` | File is read through a bounded text-only API | Cloud upload or semantic embedding claim |
| Index storage | `project-file-index.json` in app-private storage | Source-hash-linked chunks persist atomically | Cross-project access |
| Retrieval | Local lexical scoring | Project-filtered passages receive stable citations | Presentation as a semantic/vector match |
| RAG injection | `RagRetriever` | Returns `file-{fileId}-{ordinal}` citations and source/chunk provenance | Execution of instructions found in a document |

## Safety and lifecycle

The indexer reads at most 250 KiB from an extracted text file. It creates at most 96 chunks, targets 1,200 characters per chunk, and preserves a 120-character overlap while guaranteeing forward progress. Each chunk stores project ID, file ID, file SHA-256, source name, ordinal, and text.

Before search, the manager removes chunks whose source has been deleted, lost its `INDEXED` state, changed SHA-256, or is no longer present. The RAG pipeline calls the index only with its active `projectId`, so a file indexed in one workspace cannot be retrieved for another.

The current retrieval method is deliberately labelled `LEXICAL_LOCAL`. It scores matching terms and phrase coverage deterministically; it does not claim embedding similarity, reranking confidence, or broad document parsing capabilities that are not implemented.

## Evidence

| Evidence | Contract proven |
|---|---|
| `ProjectKnowledgeTextPolicyTest` | Empty/small text rejection, bounded chunk count, forward progress, overlap, and single-segment handling. |
| `ProjectFilePolicyTest` | File name and extraction eligibility gates that the indexer relies on. |
| Kotlin compilation and selected suite | Files UI action, ProjectKnowledgeManager, ServiceLocator, and RagRetriever compile as one live path. |

The next enhancement should provide MIME-specific extractors (PDF/DOCX/XLSX), optional local embeddings, retrieval reranking, and an evidence inspector that surfaces the citation source beside model claims. Those steps must preserve the same explicit index request and project ownership gate.
