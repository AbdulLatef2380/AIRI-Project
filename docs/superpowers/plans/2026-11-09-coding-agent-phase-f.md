# CodingAgent Phase F Vertical Slice Implementation Plan

> **For agentic workers:** This plan is executed task-by-task on `main`.

**Goal:** Add a production-safe CodingAgent orchestration contract that turns a planned coding request into bounded ToolExecutionPipeline calls inside an AgentSandbox and returns structured execution evidence.

**Architecture:** The orchestration layer owns request identity, ordered action execution, bounded model-context reporting, and terminal status. Tool parsing, authorization, approval, rate limiting, sandbox execution, and handlers remain owned by ToolExecutionPipeline. The agent never accesses Android filesystem APIs or ProcessBuilder.

**Tech Stack:** Kotlin, coroutines, existing ToolExecutionPipeline, AgentSandbox, JVM unit tests.

**Spec:** `/home/ubuntu/upload/pasted_content.txt` and `docs/TRACE_CODING_AGENT_EXECUTION_PLAN_AR.md`, sections F-1 through F-4.

## Global Constraints

- Every action must pass through `ToolExecutionPipeline`.
- Sandbox execution is mandatory and workspace/goal scoped.
- A failed or timed-out action stops the current run; later actions are not executed.
- Reports contain bounded, redacted evidence rather than raw arguments or unbounded tool output.
- No direct filesystem or ProcessBuilder access from CodingAgent.

## Review Focus

- Empty plans must fail closed without invoking tools.
- A tool failure must halt the plan and preserve the failed reason.
- Cancellation must remain coroutine cancellation.
- Long output must be bounded and secrets redacted before model context.
- Workspace identity must remain stable for every action in one run.

### Task 1: CodingAgent orchestration contract

**Files:**
- Create: `app/src/main/java/com/airi/assistant/agent/coding/CodingAgentOrchestrator.kt`
- Create: `app/src/test/java/com/airi/assistant/agent/coding/CodingAgentOrchestratorTest.kt`

**Interfaces:**
- Consumes a `ToolExecutionPipeline` through `ToolPipelineCodingExecutor`, an `AgentSandbox` through `AgentSandboxToolSandbox`, and a planned list of typed `CodingAction` values.
- Produces `CodingAgentExecutionReport` with ordered step evidence and a bounded `modelContext()` string.

- [x] Define request-bound action and report types.
- [x] Execute actions sequentially through the pipeline and stop on non-success.
- [x] Preserve cancellation and emit bounded/redacted evidence.
- [x] Add tests for success, failure short-circuit, empty plan, output bounds, and cancellation.
- [ ] Run the targeted JVM test and repository structural checks; JVM execution is currently blocked by the missing Android SDK.

### Task 2: Coding tool boundary policy

**Files:**
- Create: `app/src/main/java/com/airi/assistant/agent/coding/CodingToolBoundaryPolicy.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/coding/CodingToolBoundaryPolicyTest.kt`

The policy validates workspace-relative paths, traversal, file/list/search/patch bounds, and terminal allowlist, approval, timeout, output, and working-directory constraints before a pipeline handler runs.

- [x] Define bounded decisions for read/list/search/write/apply-patch/terminal actions.
- [x] Reuse `TerminalExecutionPolicy` instead of starting processes from CodingAgent.
- [x] Add regression tests for traversal, limits, shell metacharacters, approval, timeout, and output bounds.

### Task 3: Bounded repair and verification loop

**Files:**
- Create: `app/src/main/java/com/airi/assistant/agent/coding/CodingRepairLoop.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/coding/CodingRepairLoopTest.kt`

The loop replans only after a `run_tests` or `build` failure, requires the new plan to contain both `apply_patch` and a final verification action, rejects duplicate plans, preserves bounded failure/diff/verification evidence, and stops after a configured attempt limit.

- [x] Add a test/build failure trigger and bounded replanning contract.
- [x] Require patch plus verification in every repair plan.
- [x] Preserve failure reason, patch evidence, verification evidence, and remaining status.
- [x] Add tests for successful repair, invalid repair, non-repairable failure, and attempt exhaustion.

### Task 4: Dedicated test/build verification policy

**Files:**
- Create: `app/src/main/java/com/airi/assistant/agent/coding/CodingVerificationPolicy.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/coding/CodingToolBoundaryPolicy.kt`
- Modify: `app/src/main/java/com/airi/assistant/agent/coding/CodingAgentOrchestrator.kt`
- Test: `app/src/test/java/com/airi/assistant/agent/coding/CodingVerificationPolicyTest.kt`

`run_tests` and `build` now use a policy separate from generic terminal execution. It accepts only bounded Gradle verification tasks, keeps the working directory and artifact path inside the workspace, and validates exit code, duration, and bounded output through a typed result contract.

- [x] Add allowlisted Gradle test/build command admission.
- [x] Reject generic terminal commands, shell syntax, unsafe tasks, traversal, timeout, and output overflow.
- [x] Add typed `CodingVerificationResult` with `passed`, exit code, duration, output, and artifact reference.
- [x] Wire `run_tests`/`build` into CodingAgent preflight and add regression tests.
