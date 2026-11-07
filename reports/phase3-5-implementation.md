# AIRI — Phases 3–5 implementation record

## Phase 3 — ExecutionTraceArchive reliability

The existing file-backed archive remains the storage mechanism. It now parses a candidate completely before publishing it, prefers the primary archive, and can recover from a validated `.bak` or `.tmp` candidate when the primary file is missing, empty, or corrupt. Writes are serialized, validated through a JSON parse, preserve the previous primary as `.bak`, and replace the primary through the existing atomic move path where supported. A failed replacement no longer falls back to truncating the healthy primary file. Loading is bounded by `maxMessages`, and corrupt input produces an empty in-memory view rather than partially parsed entries.

Added coverage for backup recovery and primary-over-stale-temp precedence in `ExecutionTraceArchiveTest`.

## Phase 4 — AgentLoop decomposition

The existing AgentLoop behavior was preserved. The tool-surface admission decision was extracted into the pure `AgentLoopToolAdmission` policy. It is responsible only for deciding whether a schema may enter the loop based on typed durable context, the calendar runtime, and the terminal gate. It does not execute tools or change tool contracts. Unit coverage verifies read-only admission, calendar prerequisites, and independent terminal gating.

## Phase 5 — connector diagnostics

The existing connector and runtime contracts remain in place. `ConnectorDiagnosticsStore` records only connector id, stable error code/class, duration, timestamps, consecutive failures, and whether rate limiting was observed. It never stores request text, response bodies, credentials, or URLs. `ConnectorRuntimeManager` publishes the bounded diagnostic snapshots and records success, failure, timeout, and approval outcomes. Error classes cover authentication, authorization, rate limit, network, timeout, invalid request, not found, provider, and internal failures.

`ConnectorOutput.Failure` now has an optional `retryAfterMs`; the runtime honors it with a 60-second upper bound and otherwise keeps the existing exponential-style bounded delay. Existing callers remain source-compatible because the field has a default value.

## Verification status

Static checks passed:

- localization health (strict)
- release health
- `git diff --check`

Targeted Gradle tests were attempted but could not start in this sandbox because no Android SDK is installed and neither `ANDROID_HOME` nor `local.properties` points to one. No test failure or Kotlin compilation result is claimed from those attempts. The next verification authority is the GitHub Actions Android build after this commit is pushed.
