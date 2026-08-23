# Artifact Provenance Contract

**Status:** `IMPLEMENTED` / `TESTED` / `BUILD_VERIFIED` for persisted project and successful `ProductionAgentOrchestrator` step-result artifacts. Device migration and file/share verification remain `RUNTIME_VERIFICATION_PENDING`.

## Purpose

Every AIRI artifact must be attributable to the project and, when it comes from agent execution, to the exact task, run, and step that produced it. An artifact reference is not sufficient evidence unless its durable metadata, stored file, Room record, task timeline, and project-scoped read path agree.

| Concern | Implemented contract |
|---|---|
| Data model | `ArtifactManager.Artifact` and `ArtifactEntity` persist `projectId`, optional `taskId`/`runId`/`stepId`, `toolId`, `modelId`, bounded provenance summary, and a SHA-256 content hash. |
| Database | `AiriDatabase` is v9. `MIGRATION_8_9` adds provenance columns, maps legacy `projectId` to `sessionId`, and creates project and execution-coordinate indexes. |
| Write boundary | `ArtifactManager.createArtifact` validates bounded, non-secret provenance before writing a file or inserting Room metadata. Task ownership requires the active durable task project, run, and a matching `RUNNING` plan step. |
| Live producer | `ProductionAgentOrchestrator` stores non-empty successful step results as private text artifacts with `projectId=plan.projectId`, `taskId=plan.id`, `runId=plan.id`, `stepId=task.id`, and first known tool identifier when available. |
| Execution evidence | `DurableTaskManager.linkArtifact` attaches the artifact only when the exact run/step is active and writes an `ARTIFACT_CREATED` timeline event. A rejected linkage deletes the just-created file/metadata. |
| Read boundary | `ArtifactManager.forProject`, `getArtifactForProject`, and `readContentForProject` constrain reads. `ProjectContextResolver` admits only `forProject(projectId)` metadata; no path, hash, content, prompt, or secret is inserted into model context. |
| User evidence | `LibraryArtifactRow` renders task/run/step, bounded tool/model IDs, provenance summary, and only the first 12 characters of the integrity hash for artifacts with complete execution coordinates. It never renders file paths, artifact content, raw prompts, or secrets as provenance. |
| Legacy compatibility | Old session-only records migrate with `projectId=sessionId`; task/run/step remain null. They are visible only through that mapped project and are not retroactively attributed to an execution. |

## Live execution sequence

1. `ProductionAgentOrchestrator` registers the durable task and starts its run.
2. Before a sub-agent executes, `DurableTaskManager.updateExecutionStep` marks its plan step `RUNNING` with the owning run.
3. A successful non-empty result reaches `persistStepArtifact` before step completion.
4. `ArtifactManager` validates project/task/run/step ownership and writes a private artifact plus Room v9 metadata and SHA-256 integrity value.
5. `DurableTaskManager.linkArtifact` records the artifact ID and `ARTIFACT_CREATED` timeline evidence for the exact step. If that link is rejected due to a stale/racing execution, the artifact is deleted rather than retained without valid provenance.
6. The orchestrator marks the step complete and downstream tasks receive the result through the existing workspace dependency path.

> `modelId` is persisted only when the producer supplies a bounded model identifier. The current orchestrator result producer does not infer a model identifier; it leaves that field null rather than inventing provenance.

## Evidence

| Evidence | Result |
|---|---|
| `ArtifactProvenanceTest` | Accepts complete bounded project/task/run/step metadata and rejects partial coordinates or secret-like summaries. |
| `DurableTaskProductKernelTest` | Confirms an artifact ID is attached while the owning run/step is active and validates through MissionKernel normalization. |
| `AiriDatabaseMigrationTest` | Instrumentation-ready migration harness now asserts v9 provenance columns and indexes from v1 data. |
| Targeted Android unit build | `ArtifactProvenanceTest` and `DurableTaskProductKernelTest` passed with compilation of Room v9 and the orchestrator path. |
| Core verifier | Source checks require Room migration, artifact project scope, provenance validation, orchestrator creation, durable linkage, and Library evidence rendering. |
| Android instrumentation compilation | Library evidence UI and the v9 artifact path compile with the Android test target. |

## Remaining closure work

Artifact producers outside `ProductionAgentOrchestrator` must supply the same `ArtifactProvenance` contract before they can claim task evidence. Physical-device migration, file preview/share/download, evidence rendering at accessibility/font-scale extremes, deletion races, and a credentialed multi-step agent run remain external/runtime evidence. No artifact content, raw path, raw prompt, credential, or provider response is admitted to RAG context through this contract.
