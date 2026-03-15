
com.airi.assistant
│
├── app
│   ├── AIRIApplication.kt
│   └── MainActivity.kt
│
├── core
│   ├── AiriCore.kt
│   ├── UnifiedCognitiveLoop.kt
│   ├── IntentRouter.kt
│   ├── AiriPersona.kt
│   ├── AuditManager.kt
│   ├── SystemControl.kt
│   │
│   └── intent
│       └── IntentType.kt
│
├── ai
│   ├── LlamaManager.kt
│   ├── LlamaNative.kt
│   └── PromptBuilder.kt
│
├── memory
│   ├── entity
│   │   ├── BehaviorStatsEntity.kt
│   │   ├── ContextCacheEntity.kt
│   │   ├── MemoryEntities.kt
│   │   └── UsageStatEntity.kt
│   │
│   ├── dao
│   │   ├── BehaviorStatsDao.kt
│   │   ├── ContextCacheDao.kt
│   │   ├── MemoryDao.kt
│   │   └── UsageStatsDao.kt
│   │
│   ├── repository
│   │   ├── ContextEngine.kt
│   │   └── MemoryManager.kt
│   │
│   └── AiriDatabase.kt
│
├── agent
│   │
│   ├── planning
│   │   ├── ActionPlan.kt
│   │   ├── ActionPlanner.kt
│   │   ├── AgentGoal.kt
│   │   ├── AiriBrainController.kt
│   │   ├── AiriIntent.kt
│   │   ├── BrainIO.kt
│   │   ├── BrainManager.kt
│   │   ├── GoalExecutor.kt
│   │   ├── IntentEngine.kt
│   │   ├── PlanGenerator.kt
│   │   ├── PlanScorer.kt
│   │   ├── PlanStep.kt
│   │   ├── PlanValidator.kt
│   │   ├── RecoveryManager.kt
│   │   ├── UIMemory.kt
│   │   ├── ValidationException.kt
│   │   └── GracefulDetachmentProtocol.kt
│   │
│   ├── decision
│   │   ├── AdaptiveBehaviorEngine.kt
│   │   ├── AdaptiveDecisionEngine.kt
│   │   ├── BehaviorPolicy.kt
│   │   ├── ConfidenceScorer.kt
│   │   ├── DecisionEngine.kt
│   │   ├── SuggestionScoreEngine.kt
│   │   ├── EmotionEngine.kt
│   │   ├── GuardianEngine.kt
│   │   ├── PolicyEngine.kt
│   │   ├── VoiceManager.kt
│   │   ├── PatternAggregator.kt
│   │   ├── DialogueRhythmEngine.kt
│   │   └── RelationshipBoundaryPolicy.kt
│   │
│   ├── execution
│   │   ├── ExecutionLogger.kt
│   │   ├── ExecutionRecord.kt
│   │   ├── ExecutionResult.kt
│   │   ├── ExperienceStore.kt
│   │   │
│   │   ├── command
│   │   │   ├── AccessibilityCommandBridge.kt
│   │   │   ├── CommandResult.kt
│   │   │   └── CommandRouter.kt
│   │   │
│   │   ├── context
│   │   │   └── ContextProvider.kt
│   │   │
│   │   ├── node
│   │   │   ├── NodeActionExecutor.kt
│   │   │   ├── NodeMatcher.kt
│   │   │   ├── NodeScanner.kt
│   │   │   └── SemanticRanker.kt
│   │   │
│   │   ├── runtime
│   │   │   ├── AgentExecutor.kt
│   │   │   ├── ExecutionContext.kt
│   │   │   ├── ExecutionState.kt
│   │   │   └── StepResult.kt
│   │   │
│   │   ├── task
│   │   │   ├── TaskChain.kt
│   │   │   ├── TaskOrchestrator.kt
│   │   │   └── TaskStep.kt
│   │   │
│   │   └── validation
│   │       ├── TemporalValidator.kt
│   │       └── UiStateHasher.kt
│   │
│   └── learning
│       ├── InteractionTracker.kt
│       ├── UILearningEngine.kt
│       ├── EthicalMemoryController.kt
│       │
│       └── reinforcement
│           ├── AdaptivePolicy.kt
│           └── ReinforcementMemory.kt
│
├── accessibility
│   ├── service
│   │   ├── AIRIAccessibilityService.kt
│   │   ├── BehaviorEngine.kt
│   │   ├── BehaviorMemory.kt
│   │   ├── ContextActionEngine.kt
│   │   ├── ContextClassifier.kt
│   │   ├── ContextIntelligence.kt
│   │   ├── IntentDetector.kt
│   │   ├── IntentPredictor.kt
│   │   ├── OverlayBridge.kt
│   │   ├── ScreenContextHolder.kt
│   │   ├── SessionMemory.kt
│   │   ├── SmartActionEngine.kt
│   │   └── SuggestionEngine.kt
│   │
│   ├── scanner
│   │   ├── NodeFinder.kt
│   │   └── UITreeScanner.kt
│   │
│   └── executor
│       └── ActionExecutor.kt
│
├── world
│   ├── WorldState.kt
│   ├── WorldStateManager.kt
│   ├── ContextSnapshot.kt
│   ├── RiskEstimator.kt
│   ├── ScreenHasher.kt
│   └── SensoryBudgetManager.kt
│
├── tools
│   ├── ModelDownloadManager.kt
│   ├── ModelDownloadService.kt
│   ├── N8nIntegration.kt
│   ├── ToolDefinition.kt
│   ├── ToolExecutor.kt
│   ├── ToolRegistry.kt
│   └── ToolScanner.kt
│
└── ui
    ├── AvatarView.kt
    ├── ChatAdapter.kt
    ├── ChatModel.kt
    ├── DebugOverlayService.kt
    ├── OverlayBridge.kt
    └── OverlayService.kt
