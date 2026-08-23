# Approval Continuation Contract

**Status:** `IMPLEMENTED` / `TESTED` for the task-scoped GitHub mutation route; broader agent-tool integration remains `PARTIAL`.

## Purpose

AIRI must never convert a consent request into a generic retry. An approval decision authorizes **one exact side effect** owned by one durable task, mission, project, run, and plan step. The side effect is persisted before it is invoked, claimed atomically once, and then executed in a fresh safe coroutine scope.

| Element | Contract |
|---|---|
| Durable owner | `DurableTask` owns `TaskApproval` and `ApprovalContinuation`; `MissionKernel` normalizes and validates task/mission/project/run/step alignment at save and load. |
| Pause | `DurableTaskManager.pauseForApproval` moves the task, current run, and current step to `PAUSED` only when the approval and continuation match the live run/step. |
| Approval decision | `PermissionGovernanceLayer` persists `APPROVED`, `DENIED`, or `EXPIRED`. Denied/expired records reject their pending continuation and cannot be claimed. |
| Claim | `claimApprovedContinuation` atomically persists `CLAIMED` **before** a connector call. A second click, late callback, restart race, or retry receives no continuation. |
| Connector authorization | A resumed connector request carries `projectId`, `taskId`, `missionId`, `runId`, `stepId`, `idempotencyKey`, and `continuationId`. `GitHubConnector` revalidates the claimed record before making its API call. |
| Retry behavior | `ConnectorRuntimeManager` returns `ApprovalRequired` without fallback or retry. A claimed external mutation executes with `maxRetries = 0`; ambiguous network failure is recorded, never silently replayed. |
| Recovery | Startup scans only continuations that were already approved, unexpired, pending, and task-paused. Each is claimed before dispatch, so recovery is also one-shot. |
| Secret boundary | The persisted invocation excludes binary payloads and credentials. Inputs resembling authorization, bearer credentials, API keys, passwords, secrets, or tokens are rejected before continuation persistence. |

## Live route

1. A task-scoped GitHub mutation reaches `GitHubConnector` with a complete `ConnectorExecutionContext`.
2. `GitHubConnector` persists a task approval and safe `ResumableConnectorInvocation`, then calls `pauseForApproval` **before** obtaining or using a GitHub credential.
3. Trust Center exposes the durable approval. **Allow** persists `APPROVED` and triggers `ApprovalContinuationRuntime.resume`; **Deny** only records denial.
4. The runtime claims the continuation, reconstructs a non-secret `ConnectorInput`, and invokes the connector once with the continuation ID.
5. The connector validates the claimed ownership/idempotency record before the API request. The runtime persists outcome and marks the exact step complete or failed.

> `PROJECT` grant scope is retained as a recorded user decision but does not bypass the exact continuation claim. AIRI does not claim project-wide replay authorization.

## Evidence

| Evidence | Result |
|---|---|
| `DurableTaskProductKernelTest` | Verifies PAUSED state, approval, one-time claim, task/run/step transition, project mismatch rejection, and secret-like payload rejection. |
| `ConnectorRuntimeManagerTest` | Verifies `ApprovalRequired` is returned without connector retry. |
| `MissionKernel` | Rejects continuation records whose approval/task/mission/project/run/step or safe invocation does not match. |
| Build | Targeted Android unit-test compilation succeeded for the stated revision. |

## Remaining closure work

The generic AgentLoop `ask_confirmation` text sentinel, skill execution, terminal commands, and other connector mutations do not yet emit this structured continuation descriptor. Their text or policy pauses must be migrated before the product-wide P0 approval-resume row can be upgraded from `PARTIAL`. Physical-device recovery and a credentialed GitHub API mutation remain `RUNTIME_VERIFICATION_PENDING`.
