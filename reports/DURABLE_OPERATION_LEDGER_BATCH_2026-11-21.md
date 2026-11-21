# Durable Operation Ledger Batch — 2026-11-21

## Scope

This batch advances the continuous-task recovery plan under P0-B1 by making the durable side-effect operation ledger a first-class Room projection. The aggregate payload remains the compatibility boundary, while the operation rows are now persisted transactionally with the task, runs, steps, external operations, and event journal.

## Changes

- Added `DurableOperationEntity` with task ownership, run/step/action identity, stable idempotency key, payload hash, provider, status, provider reference, and sanitized error metadata.
- Added Room DAO insert/delete/count operations for the ledger.
- Included operation projection replacement in the same `DurableTaskRoomStore` transaction as the aggregate snapshot.
- Added Room migration `14 -> 15` and raised `AiriDatabase` to version 15.
- Added Android instrumentation coverage for operation projection persistence and count verification alongside external-operation and event-journal persistence.

## Evidence

- Branch: `self-agent-implementation`
- Commit: recorded in the Git history for this batch
- Static verification: `git diff --check` passed.
- Build attempt: `./gradlew :app:compileDebugKotlin --no-daemon`
- Build result: blocked before compilation because this sandbox has no Android SDK and no `ANDROID_HOME`/`local.properties` SDK path.

## Boundaries

This batch does not claim full provider-side exactly-once semantics. Connector invocation, provider idempotency support, and UNKNOWN reconciliation remain governed by the existing aggregate state machine and require the provider/fake-connector integration portion of P0-B1.
