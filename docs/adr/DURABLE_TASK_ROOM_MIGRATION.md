# Durable task JSON → Room migration (AiriDatabase v12)

**Status:** transactional foundation implemented; production migration gates remain open until Android/Room validation on an SDK-enabled device/CI run.

## Storage ownership

`AiriDatabase` (`airi_memory_db`) is the only writable durable-task store on Android. Version 12 adds task aggregate, run, step, external-operation, typed timeline-event, outbox, and import-marker tables to the existing database; `AgentRunDatabase` is not used for task state. The task row stores its checksummed, versioned aggregate JSON payload for lossless domain restoration, while indexed relational projections support status/ownership lookup and operation reconciliation. Every task mutation replaces the aggregate snapshot and its child projections in one SQLite transaction; schedule intents are committed in that same database. The typed event table is a sanitized projection of the task timeline, not yet the sole event-sourced source of truth. State-version/CAS APIs remain follow-up work.

## One-time conservative import

On manager startup, `DurableTaskRoomStore` reads `{filesDir}/durable_tasks.json` only if the import marker is absent. It decodes through `DurableTaskJsonCodec`, rejects unreadable/unsupported documents, duplicate/blank IDs, and ownership-invalid tasks, then normalizes the accepted domain entities. Within one Room transaction it requires the task table and marker to be empty, inserts the complete set, reads rows back and compares their persisted payload hashes/metadata, then inserts a marker containing source SHA-256, source byte count, legacy schema version, and imported count. A thrown validation/read-back/write error aborts the whole transaction. Once committed, subsequent startups load and verify Room rows and the marker rather than importing again.

The legacy file is never written, renamed, truncated, or deleted by this path. On failure it remains the recovery source, and `DurableTaskManager` blocks further mutations rather than treating the failure as an empty task list. An absent JSON file is represented as a completed empty import with an empty-source hash; no file is created.

## Transactional outbox

New queued work writes the durable task row and a stable `schedule-task:<taskId>` outbox event in the same Room transaction. After commit, the manager dispatches pending schedule events to WorkManager with the existing unique-work name and `KEEP` policy, then marks the event dispatched. A crash between enqueue and marking can redeliver the same event, but `KEEP` plus the stable task identity makes this dispatch idempotent. WorkManager is explicitly outside the SQLite atomicity claim. Failed dispatch leaves the event pending for startup reconciliation.

## Verification status and remaining gates

Instrumentation tests cover successful import and byte-for-byte source retention, idempotent reopen, import rollback after inserting rows but before committing its marker, malformed/future JSON fail-closed behavior, atomic rollback of a task plus outbox intent, duplicate schedule-event idempotency, relational projections, and the v1→v12 Room migration's data/table/index checks. In this environment, `:app:kspDebugKotlin`, `:app:testDebugUnitTest`, and `:app:compileDebugAndroidTestKotlin` passed; a SQLite comparison also matched all seven new migration tables to the exported Room schema (columns, primary/foreign keys, and indices). Instrumentation itself has not run because no Android emulator/device is attached. Do not delete or replace the JSON source, nor claim full migration acceptance, until the instrumentation tests run on Android and a DB backup/restore round-trip is verified. Remaining storage gates include empirical migration sizing, restore testing, CAS/stateVersion, wider fault-injection, and the existing external-operation scheduler's integration with outbox dispatch; the JSON source remains retained.
