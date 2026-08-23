# AIRI Project File Intelligence Contract

> **Status:** `IMPLEMENTED` / `BUILD_VERIFIED` for local project-file import, isolation, lifecycle persistence, duplicate detection, managed storage, preview extraction for declared text formats, explicit local knowledge indexing, soft delete/restore/purge, Library UI, and account-data wipe wiring. `RUNTIME_VERIFICATION_PENDING` for picker flows, actual storage behaviour, and instrumentation execution on a device/emulator.

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
                                                │                         │
                                                └── FAILED                └── DELETED → RESTORE → READY
                                                                             │
                                                                             └── PURGE (terminal)
```

The manager performs real operations at each transition. It opens the Android document URI, copies bytes into managed private storage while enforcing a 100 MB ceiling, calculates a SHA-256 digest, rejects a duplicate digest within the same project, stores the managed file, reads a bounded preview only for declared text formats, and persists the metadata atomically in app-private storage.

`INDEXING` here means the file has reached the point at which it can be sent to an indexer. The import path then sets `indexState = NOT_REQUESTED` before `READY`; AIRI does not pretend the file is searchable through knowledge retrieval until the user or a governed workflow explicitly requests indexing. A later real indexer may call `requestIndex` and `markIndexed`.

## Security and isolation

A file must have a non-empty project ID. Deduplication is project-scoped, so equal content may exist in separate projects without leaking their association. The library projects only records owned by the active workspace. It does not write source text into the activity stream, model context, task diagnostics, or cloud storage.

A deletion first copies the managed backing file to an app-private, project-owned trash path. Only after that archive succeeds does it remove the active media-library copy, mark the record `DELETED`, clear its preview, and remove that file's knowledge index through the explicit wiring in `ServiceLocator`. A deleted file is excluded from active project listings, search, context admission, and knowledge retrieval.

`restore(id)` copies the private trash file back through `MediaLibrary` under the same project, performs bounded preview extraction, resets the knowledge index state to `NOT_REQUESTED`, and removes the trash copy only after the managed file exists again. Knowledge is never silently restored: the user must explicitly index the restored file. `purge(id)` permanently removes the trash copy and metadata. `deleteAll()` removes active files, trash, in-memory records, and persisted indexes; `DataDeletionCoordinator` invokes it together with `ProjectKnowledgeManager.deleteAll()` during account filesystem wipe.

## User-visible behaviour

The Files screen requires an active project. The Android document picker invokes the real URI import path. The screen separates **Project files**, **Recently deleted files**, and **Generated artifacts**, supports exact local search over active name, MIME type, and tags, allows a favorite flag and soft deletion, and exposes bounded preview plus extraction and knowledge-index states. The deleted section provides explicit restore and permanent-delete controls; deleted records cannot be indexed or shown as active project context.

## Automated evidence

| Test | Evidence |
|---|---|
| `ProjectFilePolicyTest` | Filename sanitization and deterministic text/binary extraction eligibility. |
| `WorkspaceContextTest` | Project file projection ignores deleted files and files owned by other projects. |
| `ProjectResourceIsolationTest` | Instrumentation-ready A/B fixture composes real file import, explicit knowledge indexing, soft delete/restore/reindex, and artifact project-read denial. |
| Android instrumentation compilation | Picker, `ProjectFileManager`, Library UI, knowledge cleanup, account-data wipe wiring, and the fixture compile together. |

The remaining device-specific acceptance check is to import a document through the Android picker, close/reopen the app, confirm the active project still lists it, soft-delete it, confirm that active knowledge retrieval no longer returns it, restore it, re-index it explicitly, and finally purge it while verifying the private trash copy is removed.
