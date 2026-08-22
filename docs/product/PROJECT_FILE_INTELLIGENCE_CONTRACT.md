# AIRI Project File Intelligence Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for local project-file import, isolation, lifecycle persistence, duplicate detection, managed storage, preview extraction for declared text formats, and Library UI. `RUNTIME_VERIFICATION_PENDING` for picker flows and device storage behaviour.

AIRI distinguishes the following resources instead of treating every upload as the same object.

| Resource | Owner | Purpose | Implicit knowledge ingestion |
|---|---|---|---|
| Attachment | A chat turn | Supplies a message-specific input | Never |
| Project file | A workspace/project | User-controlled durable local resource | Never |
| Artifact | A task/agent result | Generated output with version history | Never |
| Knowledge source | A collection | Explicitly indexed retrieval material | Only after an explicit index request and successful indexer result |
| Memory | User/project/session scope | Persisted learned fact or preference | Never from file import alone |

## Lifecycle

```text
IMPORTING → VALIDATING → HASHING → STORING → EXTRACTING → INDEXING → READY
                                                │
                                                └── FAILED
```

The manager performs real operations at each transition. It opens the Android document URI, copies bytes into managed private storage while enforcing a 100 MB ceiling, calculates a SHA-256 digest, rejects a duplicate digest within the same project, stores the managed file, reads a bounded preview only for declared text formats, and persists the metadata atomically in app-private storage.

`INDEXING` here means the file has reached the point at which it can be sent to an indexer. The import path then sets `indexState = NOT_REQUESTED` before `READY`; AIRI does not pretend the file is searchable through knowledge retrieval until the user or a governed workflow explicitly requests indexing. A later real indexer may call `requestIndex` and `markIndexed`.

## Security and isolation

A file must have a non-empty project ID. Deduplication is project-scoped, so equal content may exist in separate projects without leaking their association. The library projects only records owned by the active workspace. It does not write source text into the activity stream, model context, task diagnostics, or cloud storage.

A deletion removes the managed backing path where available, marks the persisted record `DELETED`, and clears its preview. This preserves an auditable lifecycle record without leaving user content available through the active library.

## User-visible behaviour

The Files screen requires an active project. The Android document picker invokes the real URI import path. The screen separates **Project files** from **Generated artifacts**, supports exact local search over name, MIME type, and tags, allows a favorite flag and deletion, and exposes bounded preview plus extraction and knowledge-index states.

## Automated evidence

| Test | Evidence |
|---|---|
| `ProjectFilePolicyTest` | Filename sanitization and deterministic text/binary extraction eligibility. |
| `WorkspaceContextTest` | Project file projection ignores deleted files and files owned by other projects. |
| Kotlin compilation | Android picker, `ProjectFileManager`, Library UI, and runtime wiring compile together. |

The remaining device-specific acceptance check is to import a document through the Android picker, close/reopen the app, confirm the active project still lists it, and then delete it while verifying its managed file is unavailable.
