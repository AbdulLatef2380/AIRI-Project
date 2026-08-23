# AIRI AgentLoop Durable Approval Migration Contract

> **Status:** `AUDITED` / `DESIGNED`. No AgentLoop side-effect tool is currently eligible to claim durable approval/recovery. The chat-owned loop has no `DurableTask`, mission, run, or step handle, while its dispatcher invokes Android and local write adapters immediately. This document defines the required migration boundary; it is **not** evidence that the sentinel confirmation path is durable.

## Observed runtime boundary

`ChatViewModel` creates an `AgentLoop` for a chat turn and passes a project identifier only to memory/RAG and chat-message persistence. `AgentLoop.run` receives no task/run/step context. `ToolDispatcher` consequently receives only a tool name, string arguments, and Android context. `ask_confirmation` emits `CONFIRMATION_REQUIRED|…`, and `ChatViewModel` suspends an in-memory dialog before returning a sentence to the same loop history.

> The current confirmation accepts or declines continuation of a **live coroutine only**. Process death, cancellation, timeout, an updated screen, or a second device cannot safely reconstruct the operation.

| Tool family | Current operation | Durable migration eligibility | Reason |
|---|---|---:|---|
| `read_screen`, `web_search`, `fetch_url`, `memory_recall`, `calendar_read` | Observation or bounded read | Not a write continuation | Preserve policy/privacy checks; do not manufacture approvals. |
| `calendar_create` | `CalendarContract` insert | First typed write candidate | Structured title/start/duration can be validated and stored as a private proposal after a task-owned agent session exists. |
| `create_note` | App-private JSON write | Later typed write candidate | Needs project/scope ownership and note revision semantics before it can be resumed safely. |
| `set_alarm`, `open_app`, `tap`, `type_text`, `scroll_down`, `go_back` | Android intent/accessibility action against volatile device UI | Not resumable under current contract | Screen/UI state changes outside AIRI; replay could duplicate or operate against a different target. Requires an explicit live-device takeover operation model, not connector continuation reuse. |
| `skill_*` | Bridge-specific | Deferred | Each skill must expose a typed, idempotent operation contract before registration. |

## Required task-owned agent session

A migration starts by creating `AgentLoopExecutionContext` at the boundary that launches a tool-capable chat run. The context must contain **only** identifiers and non-sensitive policy facts:

| Field | Required invariant |
|---|---|
| `taskId`, `missionId`, `runId`, `stepId` | Existing, active durable task/run/step; validation must fail closed before tool proposal or execution. |
| `projectId` | Exact active workspace when a project is selected; an explicit user scope is required for unscoped personal system actions. Empty text must not silently mean either project. |
| `agentId` | Registered agent principal, never a tool name. |
| `sourceSessionId` | Local chat session reference only; no chat history or prompts in a continuation. |
| `toolName` | Must map to one declared operation type and approval policy. Unknown or dynamic `skill_*` tools cannot enter the durable write path. |

The context is created once for a tool-capable run through `DurableTaskManager.registerInProcess`, `beginRun`, and an explicit plan step. It must be completed, failed, or cancelled when the loop reaches a terminal outcome. A chat response that uses no side-effecting tool may remain non-durable.

## First migration: calendar create

`calendar_create` is the first migration target because its intended write is structured and can be validated before Android I/O. It does **not** use the connector invocation type.

1. The loop parses and normalizes title, start instant, and bounded duration into a **private proposal** owned by the exact task/project or explicit personal scope.
2. It computes an operation identity from canonical fields and stores a typed descriptor containing proposal ID, normalized field hashes, calendar account selector policy, and one idempotency key. Raw title and time text do not enter task JSON, timeline summary, diagnostics, artifact provenance, or logs.
3. Before calling `CalendarTool.createEvent`, the runtime creates `TaskApproval` and pauses the exact step with a dedicated `ResumableCalendarCreate` descriptor.
4. Trust Center grants once, then the calendar runtime claims the descriptor atomically. It revalidates task/mission/project/run/step, proposal integrity, permission state, and expiry before one insert attempt.
5. The adapter records only the local provider row identifier/operation state required to prevent duplicate insert. It must not retry automatically after an unknown provider outcome.
6. A success creates bounded evidence and completes the durable step. A denied/expired approval wipes private proposal material. A failed or ambiguous write marks the step failed with a redacted reason and offers a fresh user-initiated proposal rather than replay.

## Non-negotiable safety rules

The sentinel `CONFIRMATION_REQUIRED|…` must not be treated as authorization for the next arbitrary tool call. Until a typed runtime owns a specific side effect, the model can ask the user a conversational question but it cannot convert that answer into a durable approval claim.

`AgentLoop` must never enqueue a generic callback, serialize raw tool arguments, or replay accessibility/intent actions after recovery. A tool call that cannot prove idempotency and target identity remains direct user-control/takeover work and must expose that limitation instead of claiming automated execution.

## Acceptance evidence for the migration

| Gate | Required evidence |
|---|---|
| Ownership | JVM test rejects missing/stale task, mission, project, run, or step before proposal and after recovery. |
| Privacy | Descriptor/JSON/log tests prove raw calendar text, prompts, secrets, and chat history are absent. |
| Claiming | Concurrent claim test proves one adapter invocation; rejected/expired approval removes proposal. |
| Provider failure | Permission denied, insert failure, and ambiguous outcome tests do not retry or duplicate the event. |
| UI | Trust Center shows a bounded action summary and Library/Execution evidence without raw private fields; rejection/cancellation and process recreation are device-tested. |
| Android runtime | Instrumentation validates calendar permissions and provider behavior on a real emulator/device. |

## Explicit exclusions

This contract does not authorize arbitrary accessibility automation, app launch, typing, alarms, skill execution, browser interaction, connector mutation, desktop/VNC control, or scheduled replay. Each requires a separately typed operation, ownership model, policy, and test evidence.
