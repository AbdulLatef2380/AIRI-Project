# AIRI Integrated Execution Closure

**Date:** 2026-10-02  
**Branch:** `cp-foundation`  
**Main branch:** unchanged  
**Scope:** unified live connector exposure and execution inside AgentLoop.

## Executive result

The execution gap was closed. Live connector actions are now projected into the same `ToolSchema` surface used by AgentLoop, while execution is routed through the canonical connector runtime:

```text
live ConnectorRegistry
  → ConnectorToolBridge
  → ToolSchema
  → AgentLoop
  → ToolDispatcher
  → ConnectorRuntimeManager
  → Connector
  → ConnectorOutput
  → ToolResult / model continuation
```

## Implemented contract

- `Connector.agentActions()` is the extensibility contract for current and future connectors.
- `ConnectorAgentAction` carries action ID, description, permission level, and parameters.
- `ConnectorToolBridge` derives schemas from registered live connectors only.
- Exposure requires `connected && healthy` and a `READ` action.
- Catalog-only entries, disconnected connectors, unhealthy connectors, and write/destructive actions are not exposed.
- Execution uses `ConnectorRuntimeManager`; the bridge never calls provider HTTP adapters directly.
- A disconnect between exposure and execution remains resolvable as a known tool and returns the stable runtime failure (`not_connected` / `unhealthy`) rather than `unknown_tool`.

## Vertical slices

### GitHub read actions

`list_repos`, `list_issues`, `search_code`, `get_file`, `list_prs`, `status`.

### Telegram read actions

`get_updates`, `get_chat_info`, `status`.

### Google read actions

`gmail_list`, `gmail_read`, `calendar_list`, `drive_search`.

No send, create, update, delete, or destructive provider action is exposed by this slice.

## Evidence and safety

- Runtime path classification records connector tools as `CONNECTOR_RUNTIME`.
- Runtime trace remains redacted and does not store credentials, headers, or message bodies.
- No provider connection is triggered during tool exposure.
- No external write operation was performed during implementation or tests.
- Legacy connector implementations were not deleted; canonical coverage is established before any removal decision.

## Verification

### Targeted integration tests

Passed:

- dynamic read-only exposure
- write-action exclusion
- disconnected/unhealthy exclusion
- canonical ConnectorRuntimeManager execution
- stable disconnect race failure
- universal execution path classification
- capability discovery
- runtime trace
- text tool protocol

### Full unit regression

```text
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest
BUILD SUCCESSFUL
```

### Static core verification

```text
python3 tools/verify_core_changes.py
summary: 96/96 checks passed
```

### Attachment security scan

```text
status: SOURCE_VERIFIED
```

### Debug APK

```text
./gradlew --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL
AIRI_VERIFY_NATIVE: debug APK contains lib/x86_64/libairi_native.so
```

Artifact:

- `app/build/outputs/apk/debug/app-debug.apk`
- Size: `117016283` bytes
- SHA-256: `5c051507d79909f173b05d3ffdd54b6afb74a518a6edf9f92dd241a410342d48`

The only build warning was that `libjnidispatch.so` could not be stripped and was packaged unstripped; the build and native verification completed successfully.

## Explicit non-goals completed by exclusion

- Native provider tool calling was not introduced.
- Telegram messages were not sent.
- GitHub issues were not created.
- Google data was not read.
- `main` was not modified.
