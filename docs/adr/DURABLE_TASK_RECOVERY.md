# Durable task startup recovery

**Status:** startup reconciliation is executable through an injected persistence/scheduling boundary; Android WorkManager/device acceptance remains pending.

## Recovery policy

`DurableTaskRecoveryCoordinator` is a pure planner. A persisted `RUNNING` task is converted to a nonterminal `PAUSED` task with `WORKER_INTERRUPTED`, preserving its checkpoint and current run/step; it is never placed on the generic queue automatically because a side effect may have happened before process death. Only nonterminal `QUEUED` tasks are eligible for unique WorkManager scheduling. `WAITING_EXTERNAL` and `WAITING_TIMER` stay with the external-operation recovery path.

`DurableTaskRecoveryRunner` executes the plan through injected callbacks. It persists every interrupted task before scheduling any queued work. A persistence exception aborts the pass before scheduling; a scheduling exception is isolated to that queued task, leaves its durable state `QUEUED`, and allows a later startup pass to retry. Production `DurableTaskManager` binds these callbacks to its Room-backed `putTask` and `submitToWorkManager` implementations. WorkManager uses the stable per-task unique name and `KEEP`, so repeated schedule requests do not create duplicate generic work.

## Verification

Unit tests cover interrupted-run pause/checkpoint preservation, exclusion of external waits from the generic queue, repeated recovery with unique-KEEP semantics, queue retry after a scheduling error, and fail-closed behavior when committing the interrupted state fails. These exercise the recovery runner with deterministic callbacks; they do not replace Android instrumentation or WorkManager test-driver/device tests.

## Remaining P0 gates

The generic worker currently invokes the registered agent with the task input. Recovery does not claim that this is a safe continuation from an arbitrary mid-task side effect. A RUNNING task is therefore paused after process interruption rather than replayed. Before automatic continuation/retry across tool side effects is enabled, the execution runtime needs explicit step/operation boundaries and an idempotency/reconciliation contract. Device tests must still cover worker constraint changes, app/process death, reboot behavior, WorkManager unique-work reconciliation, and persistence across database reopen. A DB backup/restore round trip is also outstanding; no JSON source is deleted or replaced by this recovery work.
