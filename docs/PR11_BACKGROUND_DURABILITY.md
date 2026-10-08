# PR-11 — Background durability and stage closure

## Scope

PR-11 closes the background execution path from durable state to WorkManager and back:

- WorkManager carries only a stable `taskId` reference; the durable task store remains the source of truth.
- Task attempts are counted at `beginRun` and bounded by `maxAttempts`.
- Retryable failures move the task back to a non-terminal queued state; only the final exhausted attempt writes `FAILED`.
- `CancellationException` is rethrown in workers and is never converted into a business failure.
- Empty agent completion and explicit agent failure are treated as execution failures.
- Stable unique work names are centralized in `BackgroundWorkNames` for agent, sync, re-engagement, maintenance, and durable tasks.
- Exponential WorkManager backoff is configured for durable tasks.
- Checkpoints continue to be written through the durable manager and remain resumable after process death.

## Contract artifacts

- `domain/background/BackgroundTaskContract.kt` — pure JVM state, attempt, checkpoint, idempotency, and work-name contract.
- `DurableTask.retry()` / `DurableTaskManager.markRetrying()` — non-terminal retry transition.
- `DurableTaskWorker` — cancellation-safe outcome mapping and bounded retry behavior.
- Worker callers (`AgentWorker`, `CloudSyncWorker`, retention, and maintenance registration) use stable names and do not swallow coroutine cancellation.

## Verification

Executed with JDK 17 and the repository Android SDK:

```text
./gradlew --no-daemon --project-cache-dir /tmp/airi-pr11-cache \
  :app:compileDebugKotlin :app:testDebugUnitTest
BUILD SUCCESSFUL
```

The full unit-test suite passed, including the PR-11 contract tests and the previously failing redaction and registry-routing tests.

Targeted contract tests also passed:

- `BackgroundTaskContractTest`
- `DurableTaskRetryContractTest`

## Closure gate

PR-11 is implementation-complete on the execution branch once the final `lintDebug` gate passes and the commit is pushed to `origin/pr-11-background-durable`. `main` is not modified directly.
