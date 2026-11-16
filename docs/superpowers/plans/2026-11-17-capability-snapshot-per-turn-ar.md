# Capability Snapshot Per Turn Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Ensure every AgentLoop turn gives the model a truthful, request-scoped distinction between registered, ready, exposed, context-omitted, policy-blocked, admitted, and executable tools.

**Architecture:** Keep existing registries and `RuntimeToolCatalog` as the source of truth. Add a final turn-local exposure reconciliation inside `AgentLoop`, after permission/admission and prompt-budget selection, and append it to the existing redacted platform snapshot. Improve the `write_file` failure contract without bypassing safety gates.

**Tech Stack:** Kotlin, Android, AgentLoop, JVM unit tests, existing capability inventory contracts.

**Spec:** `/home/ubuntu/upload/pasted_content.txt`, section 10.

## Global Constraints

- The snapshot is read-only and grants no permission.
- No tool is exposed merely by changing its status text.
- Terminal and side-effect safety gates remain unchanged.
- Credentials, tokens, paths, and provider payloads must not enter the snapshot.
- A success claim still requires a real tool result and verified artifact/evidence.

## Review Focus

- A registered tool removed by prompt budget must be `context_omitted`, not absent.
- A candidate rejected by permission/admission must remain registered and show a blocking reason.
- `write_file` failure must distinguish unregistered, not exposed, context omission, and missing durable context.
- A direct AgentLoop caller with no prebuilt platform snapshot must still receive a turn snapshot.
- Snapshot output must remain redacted and bounded.

---

### Task 1: Reconcile actual AgentLoop exposure

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/tool/PlatformCapabilitySnapshot.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/tool/PlatformCapabilitySnapshotTest.kt`

**Interfaces:**
- Produce `PlatformCapabilitySnapshot.turnExposureBlock(candidateToolNames, permittedToolNames, exposedToolNames, omittedToolNames)`.

- [ ] Add tests for exposed, policy-blocked, and context-omitted tools.
- [ ] Implement a bounded, redacted turn block with explicit `registered`, `ready`, `exposed`, `blocked_reason`, and `next_action` fields.
- [ ] Verify the existing snapshot tests and new tests.

### Task 2: Enforce the snapshot inside every AgentLoop turn

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/tool/CapabilityVerticalSliceTest.kt`

**Interfaces:**
- Use the exposure result already computed by `ToolExposurePipeline.resolve`.
- Append the turn block to the supplied platform snapshot, or use it as the snapshot when the caller supplied none.

- [ ] Build the turn block after runtime admission and prompt-budget selection.
- [ ] Ensure both the normal model prompt and exhausted-step summary use the reconciled block.
- [ ] Add a regression assertion that the direct turn block distinguishes omitted from blocked tools.

### Task 3: Make write_file diagnostics actionable

**Files:**
- Modify: `app/src/main/java/com/airi/assistant/agent/loop/AgentLoop.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/loop/AgentLoopToolAdmissionTest.kt`

- [ ] Include registered/ready/exposed/blocked_reason/next_action fields in the write-file admission failure message.
- [ ] Preserve the existing refusal to claim a saved artifact.
- [ ] Verify the focused admission tests and static checks.

### Task 4: Release verification

- [ ] Run `git diff --check`.
- [ ] Run focused JVM tests for snapshot, catalog, admission, and vertical slice.
- [ ] Run `python3 tools/verify_core_changes.py`.
- [ ] Run the available Gradle build/test command; record an environment limitation if Android SDK is unavailable.
- [ ] Review the final diff and only then prepare a commit/CI update.
