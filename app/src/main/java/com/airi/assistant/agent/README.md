# Agent package

This package owns planning, sub-agents, execution loops, and durable scheduled work.

## Active behavior

`AgentLoop` delegates model execution to `HybridOrchestrator`. Background tasks are created through `ScheduledJobOrchestrator` and executed by `ScheduledAgentWorker`. One-time and periodic task metadata is persisted locally, including whether network access is required and the last recorded outcome (`PENDING`, `COMPLETED`, or `FAILED`).

## Safety and limitations

A background task is not an interactive UI session. It has a bounded execution budget and must not assume foreground permissions or user interaction. Worker domain failures are recorded for the task UI; they are not blindly retried as infrastructure failures.

## Verification

The scheduler persistence and worker-result paths are covered by the project static verifier. WorkManager behavior, Doze timing, and device reboot restoration still require instrumentation and physical-device testing.
