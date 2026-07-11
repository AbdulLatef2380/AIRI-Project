# agent — Multi-Agent Orchestration

Owns task planning, sub-agent execution, durable task management, and adaptive learning.

## Architecture

```
ProductionAgentOrchestrator
  ├── SubAgentRegistry        Routes tasks to the right agent
  ├── PlanGenerator           Converts user intent to ActionPlan (with PlannerAdaptationEngine hints)
  ├── AgentLoop               Executes planned steps with tool dispatch
  ├── DurableTaskManager      Checkpoints task state to WorkManager for crash resilience
  └── adaptation/
        ├── PlannerAdaptationEngine    Learns plan quality from outcomes
        ├── StrategyEvolutionEngine    Learns optimal execution strategies
        └── AdaptiveIntelligenceEngine Records inference outcomes for model selection
```

## Active Sub-Agents (registered in SubAgentRegistry)

| Agent | Capability ID | Description |
|-------|--------------|-------------|
| `ResearchAgent` | `research` | Web fetch, summarize, fact-check |
| `AndroidAgent` | `android_automation` | Accessibility-based UI control |
| `ProductivityAgent` | `productivity` | Calendar, tasks, reminders |
| `MemoryAgent` | `memory` | Episodic/semantic recall |
| `CloudBrowserAgent` | `cloud_browser` | Screenshot + browser automation |

## DurableTaskManager

Backed by WorkManager. When an agent task crosses a tool boundary, the orchestrator calls `durableTaskManager.updateCheckpoint(taskId, stepDescription)`. On process death and restart, incomplete tasks are visible via `activeTasks()`.

## Adaptation Loop

After each task completes or fails:
1. `StrategyEvolutionEngine.recordNodeOutcome(agentId, recoveryBranch, attempts, success)` — updates strategy scores
2. `AdaptiveIntelligenceEngine.recordInferenceOutcome(...)` — updates model selection weights
3. `PlannerAdaptationEngine` injects learned hints into the next `PlanGenerator.createPlan()` call

## Status

- Core orchestration: **Production-ready**
- DurableTaskManager: **Wired** (checkpoints at every tool boundary)
- Adaptation engines: **Wired** (all three record outcomes)
- Sub-agents: 5 of 9 active (4 excluded — delegation shells requiring real backend)
