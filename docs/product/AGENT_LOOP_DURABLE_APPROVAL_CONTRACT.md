# AIRI AgentLoop Durable Approval Contract

> **Status:** `IMPLEMENTED` / `JVM_TESTED` / `BUILD_VERIFIED` for the first **project-scoped** typed write: `calendar_create`. Android Calendar provider permission, insertion, process recreation, and UI behavior remain `RUNTIME_VERIFICATION_PENDING` because no emulator or physical device was available. This contract does not make the conversational `ask_confirmation` sentinel durable.

## Durable boundary

Direct chat remains fail-closed for every side effect. `AgentLoopSideEffectPolicy` admits only `calendar_create` when, and only when, a supplied `AgentLoopExecutionContextFactory` creates a valid project-owned `AgentLoopExecutionContext`. `create_note`, `set_alarm`, `open_app`, accessibility commands, browser actions, dynamic skills, and connector mutation remain `DURABLE_CONTEXT_REQUIRED`, even when a calendar context exists.

| Tool family | Current admission | Durable state | Notes |
|---|---|---:|---|
| `read_screen`, `web_search`, `fetch_url`, `memory_recall`, `calendar_read` | Existing bounded/read path | `IMPLEMENTED` | No write continuation is manufactured. Existing policy/privacy rules remain in effect. |
| `calendar_create` | Project-scoped typed proposal path | `IMPLEMENTED` | Requires an active workspace project and creates one exact task/run/step only after this tool is requested. |
| `create_note`, `set_alarm`, `open_app`, `tap`, `type_text`, `scroll_down`, `go_back` | Blocked | `IMPLEMENTATION_PENDING` | A calendar context cannot authorize a different resource or a volatile device/UI target. |
| `skill_*`, terminal, browser, connector mutation | Blocked/deferred | `IMPLEMENTATION_PENDING` | Each family requires its own typed, idempotent operation and user-control contract. |

## Project-owned calendar flow

The context contains only the task, mission, project, run, step, registered `agent_loop` principal, and local session identifiers. It rejects malformed identifiers and non-project scopes. Chat prompt history, tool payloads, credentials, callbacks, and provider responses are excluded.

| Stage | Implemented behavior | Privacy and failure boundary |
|---|---|---|
| Context | `AgentLoopTaskRuntime` creates an in-process durable task with one `calendar_create` plan step, starts its run, and validates exact ownership. | No task is created for ordinary chat/read tools. Calendar creation is refused when no active project is selected. |
| Proposal | `CalendarCreateRuntime` accepts a bounded non-secret title, ISO-8601 instant with offset, and 5–1,440 minute duration. It stores canonical payload privately under app files and records only hashes in durable state. | Title, start time, duration, prompts, secrets, and provider responses never enter task JSON, timeline, provenance, diagnostics, or generic tool result. |
| Approval | The runtime creates `TaskApproval`, persists a mutually exclusive `ResumableCalendarCreate`, and pauses the exact run/step. | `ResumableCalendarCreate` contains proposal ID, title hash, schedule hash, fixed `PRIMARY_OR_FIRST` policy, and idempotency key only. Hybrid connector/file/calendar descriptors fail closed. |
| Review | Trust Center retrieves review data only from private proposal storage and shows the title, localized date/time, and duration in a dedicated dialog before approval. | The normal approval card and all persisted task evidence expose only a bounded generic action summary. Dismissing the review does not grant approval. |
| Claim and write | After one explicit grant, the calendar runtime atomically claims the exact continuation, revalidates task/mission/project/run/step plus descriptor/proposal hashes, then makes one `CalendarTool` provider insertion attempt. | `ToolDispatcher` rejects generic `calendar_create`, preventing bypass of the typed path. Claimed, failed, or ambiguous writes are not retried automatically. |
| Evidence and recovery | A successful insert creates and links one generic project-owned artifact, completes the continuation and task, then deletes private proposal bytes. Bootstrap resumes only approved, unclaimed calendar continuations. | If ownership, integrity, persistence, artifact creation/linking, or provider insertion fails, the task is failed with a bounded reason and private proposal bytes are removed. No replay follows an unknown outcome. Denied or expired approval also removes private payload. |

## Evidence obtained

| Gate | Current evidence | Status |
|---|---|---:|
| Descriptor exclusivity and claim-once | `DurableTaskProductKernelTest` covers calendar pause, approval, claim-once, and calendar/file/connector hybrid rejection. | `JVM_TESTED` |
| Context and policy boundary | `AgentLoopExecutionContextTest` and `AgentLoopSideEffectPolicyTest` prove identifier/principal rejection and that only calendar create is admitted with durable context. | `JVM_TESTED` |
| Source boundary | `tools/verify_core_changes.py` checks private hash-only descriptor, dispatcher refusal, runtime approval/claim/evidence/recovery hooks, Trust Center review, and redacted calendar logging. | `TESTED` |
| Kotlin and resource integration | `:app:compileDebugKotlin` succeeds; default, Arabic, Spanish, and Chinese review text keys are present. | `BUILD_VERIFIED` |
| Calendar provider and UI | Runtime permission denial, successful provider insert, private review visibility, denial cleanup, process recreation, and no duplicate insert after crash need Android instrumentation on a device/emulator. | `RUNTIME_VERIFICATION_PENDING` |

## Non-negotiable exclusions

The in-memory `CONFIRMATION_REQUIRED|…` dialogue remains conversational only. It cannot authorize an arbitrary later tool call, provide recovery, or substitute for exact-step durable approval. This implementation does not authorize personal/unscoped calendar writes, arbitrary accessibility automation, application launch, typing, alarms, skill execution, browser interaction, connector mutation, terminal commands, desktop/VNC control, or scheduled replay.
