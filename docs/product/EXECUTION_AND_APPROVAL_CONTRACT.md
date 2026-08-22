# AIRI Execution and Approval Contract

> **Status:** `IMPLEMENTATION_COMPLETE` for durable task-owned run/step replay, sanitised timeline retention, task-bound approval records, expiry, decisions after restart, and a reachable Android execution/approval surface. `PARTIAL` for arbitrary-step replay controls, durable waiting/resume continuations after approval, and platform-specific terminal/browser confirmation adapters.

AIRI now uses the Product Kernel’s `DurableTask` as the sole durable source for execution history and approval decisions. The older `AgentTrace` remains an observational UI mechanism; it is not the restart-safe execution record.

| Concern | Enforced contract |
|---|---|
| Task ownership | Every foreground orchestrator plan registers one `DurableTask` with project, owner, plan, run, and step identity. |
| Replay | A task retains at most 400 ordered `TaskTimelineEvent` records. Every event carries its run and step identifiers where available. |
| Sanitisation | Timeline text is compacted, capped at 280 characters, and redacted when it resembles a secret or authorization value. |
| Live events | The orchestrator records run start, step start/progress, tool requests, recoverable retries, step terminal state, and task terminal state. |
| Checkpoint integrity | Tool checkpoints now target the parent durable task, not the plan-step ID; they survive the path that creates the task. |
| Approval ownership | A `TaskApproval` stores action, safe description, risk, task/run/step, expiry, decision, decision scope, and reason. |
| Decision semantics | `ONCE`, `TASK`, and `PROJECT` grants are recorded explicitly. An expired or already decided approval cannot be granted again. |
| Recovery | Durable approval decisions remain actionable after process restart even when the governance layer’s in-memory notification list has been reconstructed or lost. |

## Runtime flow

```text
Plan → DurableTask registration → Run start → Step progress/tool/recovery events
     → replay timeline persisted atomically with the task

High-risk action → PermissionGovernanceLayer request
     → TaskApproval persisted with expiry and Task/Run/Step identity
     → Approval Center decision
     → governed approval decision persisted and added to replay
```

## User surface

The existing **Agent Tasks** route is now a unified work center. It keeps the scheduled-job view and adds an execution-run tab with current status, progress, cancellation, and the latest replay events. The approval tab lists pending task-bound approvals and exposes **Allow once**, **Allow task**, and **Deny** controls. It reads the durable task flow, so the screen reflects persisted state rather than an ad hoc local list.

## Evidence

| Evidence | Contract proven |
|---|---|
| `DurableTaskProductKernelTest` | Project/run/step lifecycle, cancellation/failure terminality, bounded timeline retention, and task-bound approval decisions. |
| Kotlin compilation and selected unit suite | DurableTaskManager, PermissionGovernanceLayer, ProductionAgentOrchestrator, ServiceLocator, resources, and AgentTasksScreen compile as a live path. |

The next increment must connect an approved request to a resumable continuation token for every dangerous tool, add an event-detail/retry view, and include device-level tests for process-death restoration and approval expiry notifications. It must not claim that a decision automatically resumes a tool before that continuation contract exists.
