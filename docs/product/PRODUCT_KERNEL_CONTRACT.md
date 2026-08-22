# AIRI Product Kernel Contract

> **Status:** `IMPLEMENTATION_COMPLETE` — automated contract coverage exists. Full device-level lifecycle verification remains pending until Android instrumentation completes on a physical/emulated device.

The Product Kernel defines one durable ownership path for user work. It does not introduce a parallel task repository. `DurableTask` is the canonical persisted task record, `WorkspaceRuntime.WorkspaceSession.sessionId` is the project identity, and `ProductionAgentOrchestrator` records the live execution run and plan-step transitions on that task.

## Canonical ownership

| Product concept | Canonical type | Persistence/runtime owner | Required relationship |
|---|---|---|---|
| Project | `WorkspaceSession` | `WorkspaceRuntime` | A task may declare `projectId = sessionId`. |
| Task | `DurableTask` | `DurableTaskManager` | Owns lifecycle, scopes, diagnostics, approvals, artifacts, plan and runs. |
| Run | `TaskRun` | `DurableTask.runs` | Belongs to exactly one task. |
| Step | `TaskPlanStep` | `DurableTask.plan` | Belongs to exactly one task and reports its own terminal state. |
| Artifact | `ArtifactManager.Artifact` | `ArtifactManager` | Scoped to its workspace/session; future producer linkage uses the task ID. |
| Runtime execution | `ProductionAgentOrchestrator` | Foreground agent path | Registers the plan in `DurableTaskManager` without scheduling duplicate background work. |

## Task contract

A durable task records the following product state: `id`, `projectId`, `ownerId`, lifecycle status, timestamps, current run and step, declarative plan, artifact and approval identifiers, sanitized diagnostics, memory and knowledge scopes, selected execution node, and append-only run history.

The task contract is fail-closed for lifecycle transitions. `beginRun` creates or replaces only the matching run identity. Completion, failure, and cancellation always close that run. A plan step records independent `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`, or `SKIPPED` state, so task replay does not infer success from a final chat message.

## Live execution bridge

`ProductionAgentOrchestrator.executePlan` performs the following operations before and during the live plan:

1. Registers an in-process `DurableTask` derived from the plan, project, owner, scopes, and execution node.
2. Starts the matching run without enqueueing duplicate `WorkManager` work.
3. Records a step as it starts, completes, or fails.
4. Stores progress and safe checkpoints under the parent task identity.
5. Closes the task with its final result or its first actionable failure reason.

Background `DurableTaskWorker` follows the same contract after a process restart: it restores the owner/project scope, resumes its run, injects the checkpoint, and closes the run through the manager.

## Workspace projection

`WorkspaceContext` is derived from existing session, artifact, and durable-task stores. It has no independently persisted task list. The Workspace card therefore presents artifact count, task count, active task count, and failed-task count only for the active project. Tasks from another `projectId` are excluded.

## Acceptance evidence

| Evidence | Contract covered |
|---|---|
| `DurableTaskProductKernelTest` | Project/owner/scopes, run history, plan-step transitions, completion, failure, cancellation, retry eligibility. |
| `WorkspaceContextTest` | Project task isolation and active/failed counters. |
| Existing routing, RAG, and firewall unit tests | Existing privacy and local/cloud policy invariants remain intact after context expansion. |

## Migration boundary

Records saved before this contract remain valid because all added fields have defaults. Legacy quick-chat work may temporarily have no `projectId`; it remains visible as an unscoped task but is never projected into an unrelated project. No raw secret, token, or private tool input is written to `TaskDiagnostic` or the public workspace summary.
