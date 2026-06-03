# REPORT 3 — Dead Code Report
*Generated: 2026-05-31 21:36:06*

## Summary

- Total symbols with 0 detected external callers: **179**
  *(Note: call graph is approximate — false positives expected)*


## Zero-Caller Classes/Interfaces/Objects (top 100)

| FQN | Kind | File | Line |
|---|---|---|---|
| `com.airi.assistant.MainActivity` | class | `MainActivity.kt` | 35 |
| `com.airi.assistant.accessibility.execution.ExecutionAction` | sealed | `accessibility/execution/AccessibilityExecutionEngine.kt` | 517 |
| `com.airi.assistant.accessibility.execution.ExecutionAction.LaunchApp` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 519 |
| `com.airi.assistant.accessibility.execution.ExecutionAction.TypeText` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 525 |
| `com.airi.assistant.accessibility.execution.ScreenContext` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 553 |
| `com.airi.assistant.accessibility.execution.ActionResult` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 561 |
| `com.airi.assistant.accessibility.execution.VerifyResult` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 563 |
| `com.airi.assistant.accessibility.execution.ExecutionLogEntry` | class | `accessibility/execution/AccessibilityExecutionEngine.kt` | 565 |
| `com.airi.assistant.accessibility.executor.ActionExecutor` | object | `accessibility/executor/ActionExecutor.kt` | 8 |
| `com.airi.assistant.accessibility.scanner.NodeFinder` | object | `accessibility/scanner/NodeFinder.kt` | 5 |
| `com.airi.assistant.accessibility.scanner.UITreeScanner` | object | `accessibility/scanner/UITreeScanner.kt` | 8 |
| `com.airi.assistant.accessibility.security.AccessibilityScopePolicy.PolicyMode` | enum | `accessibility/security/AccessibilityScopePolicy.kt` | 35 |
| `com.airi.assistant.accessibility.security.AccessibilityScopePolicy.PolicyState` | class | `accessibility/security/AccessibilityScopePolicy.kt` | 37 |
| `com.airi.assistant.accessibility.service.SuggestionEngine` | object | `accessibility/service/SuggestionEngine.kt` | 9 |
| `com.airi.assistant.accessibility.service.ContextClassifier` | object | `accessibility/service/ContextClassifier.kt` | 3 |
| `com.airi.assistant.accessibility.service.OverlayBridge` | object | `accessibility/service/OverlayBridge.kt` | 3 |
| `com.airi.assistant.accessibility.service.AiriAccessibilityService.ScreenState` | class | `accessibility/service/AiriAccessibilityService.kt` | 205 |
| `com.airi.assistant.accessibility.service.BehaviorMemory` | object | `accessibility/service/BehaviorMemory.kt` | 3 |
| `com.airi.assistant.accessibility.service.ContextIntelligence` | object | `accessibility/service/ContextIntelligence.kt` | 3 |
| `com.airi.assistant.accessibility.service.IntentPredictor` | object | `accessibility/service/IntentPredictor.kt` | 6 |
| `com.airi.assistant.accessibility.service.ContextActionEngine` | object | `accessibility/service/ContextActionEngine.kt` | 5 |
| `com.airi.assistant.agent.decision.ConfidenceScorer` | object | `agent/decision/ConfidenceScorer.kt` | 3 |
| `com.airi.assistant.agent.execution.ExecutionLogger` | object | `agent/execution/ExecutionLogger.kt` | 13 |
| `com.airi.assistant.agent.execution.ExecutionDao` | interface | `agent/execution/ExperienceStore.kt` | 32 |
| `com.airi.assistant.agent.execution.ExperienceDatabase` | class | `agent/execution/ExperienceStore.kt` | 49 |
| `com.airi.assistant.agent.execution.command.CommandRouter` | object | `agent/execution/command/CommandRouter.kt` | 12 |
| `com.airi.assistant.agent.execution.node.NodeMatcher` | object | `agent/execution/node/NodeMatcher.kt` | 5 |
| `com.airi.assistant.agent.execution.node.SemanticRanker` | object | `agent/execution/node/SemanticRanker.kt` | 12 |
| `com.airi.assistant.agent.execution.validation.UiStateHasher` | object | `agent/execution/validation/UiStateHasher.kt` | 6 |
| `com.airi.assistant.agent.governance.ModelGovernanceEngine` | class | `agent/governance/ModelGovernanceEngine.kt` | 41 |
| `com.airi.assistant.agent.governance.ModelDecision` | class | `agent/governance/ModelGovernanceEngine.kt` | 253 |
| `com.airi.assistant.agent.governance.GovernedParameters` | class | `agent/governance/ModelGovernanceEngine.kt` | 264 |
| `com.airi.assistant.agent.governance.ModelStrategy` | enum | `agent/governance/ModelGovernanceEngine.kt` | 270 |
| `com.airi.assistant.agent.learning.SkillOutcomeScorer.OutcomeRecord` | class | `agent/learning/SkillOutcomeScorer.kt` | 55 |
| `com.airi.assistant.agent.loop.LoopResult` | class | `agent/loop/AgentLoop.kt` | 53 |
| `com.airi.assistant.agent.loop.ConversationTurn` | sealed | `agent/loop/AgentLoop.kt` | 295 |
| `com.airi.assistant.agent.loop.tool.Param` | class | `agent/loop/tool/ToolSchema.kt` | 18 |
| `com.airi.assistant.agent.observability.ErrorRecord` | class | `agent/observability/AgentObservabilityHub.kt` | 235 |
| `com.airi.assistant.agent.observability.OrchestratorStatus` | enum | `agent/observability/AgentObservabilityHub.kt` | 236 |
| `com.airi.assistant.agent.orchestrator.OrchestratorPlan` | class | `agent/orchestrator/ProductionAgentOrchestrator.kt` | 517 |
| `com.airi.assistant.agent.orchestrator.OrchestratorTask` | class | `agent/orchestrator/ProductionAgentOrchestrator.kt` | 525 |
| `com.airi.assistant.agent.orchestrator.TaskResult` | sealed | `agent/orchestrator/ProductionAgentOrchestrator.kt` | 551 |
| `com.airi.assistant.agent.planning.AiriIntent` | class | `agent/planning/AiriIntent.kt` | 5 |
| `com.airi.assistant.agent.planning.BrainOutput` | class | `agent/planning/BrainIO.kt` | 6 |
| `com.airi.assistant.agent.planning.RecoveryBranch.Abort` | object | `agent/planning/PlanningTypes.kt` | 18 |
| `com.airi.assistant.agent.planning.ValidationException` | class | `agent/planning/ValidationException.kt` | 3 |
| `com.airi.assistant.agent.reflection.RetryDecision` | class | `agent/reflection/AdaptiveRetryPolicy.kt` | 174 |
| `com.airi.assistant.agent.workspace.WorkspaceArtifact` | class | `agent/workspace/AgentWorkspace.kt` | 213 |
| `com.airi.assistant.agent.workspace.DataFlowEdge` | class | `agent/workspace/AgentWorkspace.kt` | 221 |
| `com.airi.assistant.agent.workspace.WorkspaceSnapshot` | class | `agent/workspace/AgentWorkspace.kt` | 227 |
| `com.airi.assistant.agent.workspace.ActionLogEntry` | class | `agent/workspace/SandboxWorkspace.kt` | 30 |
| `com.airi.assistant.agent.workspace.SandboxSnapshot` | class | `agent/workspace/SandboxWorkspace.kt` | 49 |
| `com.airi.assistant.agent.workspace.WorkspaceResult.Err` | class | `agent/workspace/SandboxWorkspace.kt` | 60 |
| `com.airi.assistant.agent.workspace.WorkspaceResult.NotFound` | object | `agent/workspace/SandboxWorkspace.kt` | 61 |
| `com.airi.assistant.agent.workspace.WorkspaceSummary` | class | `agent/workspace/SandboxWorkspace.kt` | 215 |
| `com.airi.assistant.ai.SpecStats` | class | `ai/SpeculativeManager.kt` | 109 |
| `com.airi.assistant.ai.ModelConfigManager.ModelConfig` | class | `ai/ModelConfigManager.kt` | 11 |
| `com.airi.assistant.ai.ScannedModel` | class | `ai/ModelScout.kt` | 8 |
| `com.airi.assistant.ai.LlamaManager.LastInferenceMetrics` | class | `ai/LlamaManager.kt` | 155 |
| `com.airi.assistant.ai.GenerationConfig` | class | `ai/ResponseOptimizer.kt` | 9 |
| `com.airi.assistant.ai.SemanticCutResult` | class | `ai/ResponseOptimizer.kt` | 14 |
| `com.airi.assistant.ai.DeviceProfile` | class | `ai/DeviceProfile.kt` | 8 |
| `com.airi.assistant.ai.ModelStats` | class | `ai/ModelBenchmark.kt` | 3 |
| `com.airi.assistant.ai.PromptBuilder` | object | `ai/PromptBuilder.kt` | 9 |
| `com.airi.assistant.ai.ModelValidator.Inspection` | class | `ai/ModelValidator.kt` | 23 |
| `com.airi.assistant.ai.agent.AgentController` | class | `ai/agent/AgentController.kt` | 21 |
| `com.airi.assistant.ai.intent.SkillCall` | class | `ai/intent/SkillParser.kt` | 3 |
| `com.airi.assistant.ai.intent.SkillParser` | object | `ai/intent/SkillParser.kt` | 8 |
| `com.airi.assistant.ai.prompt.MemoryExtractor` | object | `ai/prompt/MemoryExtractor.kt` | 19 |
| `com.airi.assistant.ai.prompt.PromptCompressor` | object | `ai/prompt/PromptCompressor.kt` | 28 |
| `com.airi.assistant.ai.prompt.PromptCompressor.CompressedPrompt` | class | `ai/prompt/PromptCompressor.kt` | 46 |
| `com.airi.assistant.ai.prompt.PromptCompressor.CompressionStats` | class | `ai/prompt/PromptCompressor.kt` | 56 |
| `com.airi.assistant.ai.remote.RemoteResult` | sealed | `ai/remote/RemoteModelExecutor.kt` | 60 |
| `com.airi.assistant.ai.skills.AiriSkillOrchestrator.SkillMatch` | class | `ai/skills/AiriSkillOrchestrator.kt` | 81 |
| `com.airi.assistant.ai.skills.AiriSkillOrchestrator.ExecutionPlan` | class | `ai/skills/AiriSkillOrchestrator.kt` | 87 |
| `com.airi.assistant.ai.skills.ParsedIntent` | class | `ai/skills/AiriSkillOrchestrator.kt` | 162 |
| `com.airi.assistant.auth.identity.BiometricGatekeeper.Availability` | enum | `auth/identity/BiometricGatekeeper.kt` | 31 |
| `com.airi.assistant.auth.identity.SessionState` | enum | `auth/identity/SessionManager.kt` | 45 |
| `com.airi.assistant.connector.RouteResult` | class | `connector/AgentRouter.kt` | 153 |
| `com.airi.assistant.connector.ConnectorRuntimeManager.InflightAction` | class | `connector/ConnectorRuntimeManager.kt` | 21 |
| `com.airi.assistant.connector.ConnectorHealthMonitor.HealthEntry` | class | `connector/ConnectorHealthMonitor.kt` | 19 |
| `com.airi.assistant.connector.mcp.McpConnector` | class | `connector/mcp/McpConnector.kt` | 25 |
| `com.airi.assistant.connector.mcp.McpTool` | class | `connector/mcp/McpConnector.kt` | 110 |
| `com.airi.assistant.core.AiriCommand` | class | `core/SystemControl.kt` | 13 |
| `com.airi.assistant.core.SystemControlManager` | class | `core/SystemControl.kt` | 32 |
| `com.airi.assistant.core.AiriLogger` | object | `core/AiriLogger.kt` | 5 |
| `com.airi.assistant.core.debug.RuntimeState` | class | `core/debug/RuntimeState.kt` | 5 |
| `com.airi.assistant.crash.CrashReportStore.CrashReport` | class | `crash/CrashReportStore.kt` | 49 |
| `com.airi.assistant.domain.event.ChatSharePublished` | class | `domain/event/AppEvent.kt` | 72 |
| `com.airi.assistant.domain.event.ChatShareDeleted` | class | `domain/event/AppEvent.kt` | 73 |
| `com.airi.assistant.domain.event.CreditConsumed` | class | `domain/event/AppEvent.kt` | 88 |
| `com.airi.assistant.domain.experiment.ExperimentManager.Variant` | enum | `domain/experiment/ExperimentManager.kt` | 19 |
| `com.airi.assistant.domain.experiment.ExperimentManager.Experiment` | class | `domain/experiment/ExperimentManager.kt` | 21 |
| `com.airi.assistant.domain.monetization.FeatureFlag` | class | `domain/monetization/FeatureFlags.kt` | 5 |
| `com.airi.assistant.domain.monetization.FeatureFlags` | object | `domain/monetization/FeatureFlags.kt` | 12 |
| `com.airi.assistant.domain.monetization.TriggerReason.MessageThreshold` | object | `domain/monetization/PaywallTriggerEngine.kt` | 33 |
| `com.airi.assistant.domain.monetization.TriggerReason.FirstAgentExecution` | object | `domain/monetization/PaywallTriggerEngine.kt` | 34 |
| `com.airi.assistant.domain.monetization.TriggerReason.PremiumFeatureAttempt` | object | `domain/monetization/PaywallTriggerEngine.kt` | 35 |
| `com.airi.assistant.domain.monetization.TriggerReason.SpeedUpsell` | object | `domain/monetization/PaywallTriggerEngine.kt` | 37 |
| `com.airi.assistant.domain.monetization.TriggerReason.Manual` | object | `domain/monetization/PaywallTriggerEngine.kt` | 40 |


## Methodology Note

The call graph uses simple name matching, not type-aware resolution. A symbol with 0 detected callers may still be:
- Called via reflection
- Registered in XML/Manifest
- An Android framework entry point (Activity, Service, Worker)
- Overriding an interface method
Use this list as a starting point, not a definitive dead-code list.


