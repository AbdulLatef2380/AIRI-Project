# AIRI Capability Surface Runtime Evidence

**Date:** 2026-10-02  
**Branch:** `cp-foundation`  
**Purpose:** Convert capability claims from architectural assumptions into request-scoped runtime evidence.

## Correct runtime model

```text
User Request
  → AgentLoop
  → Request / Intent
  → Permission Profile
  → Capability Discovery
  → AIRI environment state
  → Capability Surface for this request
  → Tool schemas exposed to model
  → Model selection
  → Permission / approval
  → Execution
  → Result returned
  → Model continuation
```

AIRI does not grant a model universal authority. It derives an upper-bound surface for one request and still applies execution-time gates.

## Request-scoped Permission Profile

`AgentPermissionProfile` is resolved from:

- `QueryType` / request mode.
- model ID.
- provider ID.

The profile is attached to the AgentLoop trace and filters the tools before the model prompt is built.

Current policy:

| Request mode | Read tools | Skills | Connector reads | Memory recall | Automation | Approved calendar proposal |
|---|---:|---:|---:|---:|---:|---:|
| `SIMPLE` | No | No | No | No | No | No |
| `CREATIVE` | No | No | No | No | No | No |
| `ACTION` | Yes | Yes | Yes | Yes | Yes | Yes |
| `ANALYTICAL` | Yes | Yes | Yes | Yes | No | Yes |
| `UNKNOWN` | Yes | Yes | Yes | Yes | No | No |

This profile is an **upper bound**, not a grant. Tool execution still passes through `AgentSandbox`, side-effect policy, connector health, durable task ownership, and approval gates.

## Runtime stages now recorded

`UniversalRuntimeTraceRecorder` records the following stages with a request trace ID:

1. `EXISTS`
2. `REGISTERED`
3. `CONNECTED`
4. `AUTHENTICATED`
5. `HEALTHY`
6. `PERMITTED`
7. `EXECUTABLE`
8. `MODEL_COMPATIBLE`
9. `EXPOSED_TO_MODEL`
10. `MODEL_SELECTED`
11. `EXECUTED`
12. `RESULT_RETURNED`
13. `MODEL_CONTINUED`

The trace stores identifiers, stage outcomes, hashes, bounded metadata, and redacted attributes; it does not store credentials, authorization headers, raw provider payloads, or raw user message bodies.

## What is proven by code and tests

| Claim | Evidence status |
|---|---|
| Permission profile exists and is request-scoped | **Runtime wired + JVM tested** |
| Simple/creative requests receive no agent tool surface | **JVM tested** |
| Action requests can receive read tools, skills, memory, and connector reads | **JVM tested** |
| Analytical requests do not receive automation tools | **JVM tested** |
| Connector exposure is derived from live registry and health | **Runtime wired + JVM tested** |
| Write connector actions are excluded | **Runtime wired + JVM tested** |
| Tool selection is traceable | **Runtime wired** |
| Connector execution is traceable | **Runtime wired** |
| Result return and continuation are traceable | **Runtime wired** |
| Runtime path is classified as `CONNECTOR_RUNTIME` | **Runtime wired + JVM tested** |
| Full project unit tests pass | **Verified in this build** |
| Debug APK contains native runtime library | **Verified in this build** |

## What remains explicitly unclaimed

The following are not claimed as live provider evidence in this sandbox run:

- A real authenticated GitHub account completed a live read through the Android UI.
- A real authenticated Telegram bot completed a live read.
- A real authenticated Google account completed Gmail/Calendar/Drive reads.
- A particular provider/model combination has native tool-calling support.
- Every connector in the catalog has adopted `agentActions()`.
- Every model can execute every exposed skill.

Those claims require a credentialed device/integration run and corresponding trace export. The implementation now has the fields and stage events needed to record that evidence without confusing architecture with runtime proof.

## Verification results

```text
Targeted Permission Profile + trace + connector tests: BUILD SUCCESSFUL
Full unit regression + Debug APK: BUILD SUCCESSFUL
Static core verification: 96/96 checks passed
Attachment security scan: SOURCE_VERIFIED
Native APK verification: lib/x86_64/libairi_native.so present
```

Final Debug APK SHA-256:

```text
51a6e793cf9e86eb5753e531fe184341431d967b657a4e3642ad1a4b57444102
```

## Final interpretation

The accurate statement is:

> AIRI now computes and enforces a request-scoped Capability Surface for the AgentLoop, and records runtime evidence from discovery through model continuation. A model can use only the capabilities exposed by that surface and admitted by execution-time policy. Provider-authenticated success remains a separate claim requiring credentialed runtime evidence.
