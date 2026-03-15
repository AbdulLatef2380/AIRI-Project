# خارطة بنية مشروع AIRI (AIRI Project Architecture Map)

توضح هذه الوثيقة البنية المعمارية الكاملة لمشروع AIRI، مع تفصيل جميع الملفات والمجلدات داخل حزمة `com.airi.assistant`، بالإضافة إلى موارد `res`.

```
com.airi.assistant
│
├── AIRIApplication.kt
├── AiriCore.kt
├── AiriDatabase.kt
├── AiriPersona.kt
├── AuditManager.kt
├── AvatarView.kt
├── ChatAdapter.kt
├── ChatModel.kt
├── DialogueRhythmEngine.kt
├── EmotionEngine.kt
├── EthicalMemoryController.kt
├── GracefulDetachmentProtocol.kt
├── GuardianEngine.kt
├── LlamaManager.kt
├── LlamaNative.kt
├── MainActivity.kt
├── MemoryDao.kt
├── MemoryEntities.kt
├── MemoryManager.kt
├── ModelDownloadManager.kt
├── ModelDownloadService.kt
├── N8nIntegration.kt
├── OverlayService.kt
├── PatternAggregator.kt
├── PolicyEngine.kt
├── RelationshipBoundaryPolicy.kt
├── SensoryBudgetManager.kt
├── SystemControl.kt
├── VoiceManager.kt
│
├── accessibility
│   ├── AIRIAccessibilityService.kt
│   ├── ActionExecutor.kt
│   ├── BehaviorEngine.kt
│   ├── BehaviorMemory.kt
│   ├── ContextActionEngine.kt
│   ├── ContextClassifier.kt
│   ├── ContextIntelligence.kt
│   ├── IntentDetector.kt
│   ├── IntentPredictor.kt
│   ├── IntentType.kt
│   ├── NodeFinder.kt
│   ├── OverlayBridge.kt
│   ├── ScreenContextHolder.kt
│   ├── SessionMemory.kt
│   ├── SmartActionEngine.kt
│   ├── SuggestionEngine.kt
│   └── UITreeScanner.kt
│
├── adaptive
│   ├── AdaptiveDecisionEngine.kt
│   ├── InteractionTracker.kt
│   └── SuggestionScoreEngine.kt
│
├── agent
│   ├── ActionPlan.kt
│   ├── AdaptiveBehaviorEngine.kt
│   ├── BehaviorPolicy.kt
│   ├── ConfidenceScorer.kt
│   ├── ExecutionResult.kt
│   │
│   ├── command
│   │   ├── AccessibilityCommandBridge.kt
│   │   ├── CommandResult.kt
│   │   └── CommandRouter.kt
│   │
│   ├── context
│   │   └── ContextProvider.kt
│   │
│   ├── decision
│   │   └── DecisionEngine.kt
│   │
│   ├── node
│   │   ├── NodeActionExecutor.kt
│   │   ├── NodeMatcher.kt
│   │   ├── NodeScanner.kt
│   │   └── SemanticRanker.kt
│   │
│   ├── reinforcement
│   │   ├── AdaptivePolicy.kt
│   │   └── ReinforcementMemory.kt
│   │
│   ├── runtime
│   │   ├── AgentExecutor.kt
│   │   ├── ExecutionContext.kt
│   │   ├── ExecutionState.kt
│   │   └── StepResult.kt
│   │
│   ├── task
│   │   ├── TaskChain.kt
│   │   ├── TaskOrchestrator.kt
│   │   └── TaskStep.kt
│   │
│   └── validation
│       ├── TemporalValidator.kt
│       └── UiStateHasher.kt
│
├── ai
│   └── IntentDetector.kt
│
├── brain
│   ├── ActionPlanner.kt
│   ├── AgentGoal.kt
│   ├── AiriBrainController.kt
│   ├── AiriIntent.kt
│   ├── BrainIO.kt
│   ├── BrainManager.kt
│   ├── GoalExecutor.kt
│   ├── IntentEngine.kt
│   ├── IntentType.kt
│   ├── PlanGenerator.kt
│   ├── PlanStep.kt
│   ├── PlanValidator.kt
│   ├── RecoveryManager.kt
│   ├── UIMemory.kt
│   └── ValidationException.kt
│
├── core
│   ├── IntentRouter.kt
│   ├── PromptBuilder.kt
│   ├── ScreenHasher.kt
│   └── UnifiedCognitiveLoop.kt
│
├── data
│   ├── AppDatabase.kt
│   ├── BehaviorStatsDao.kt
│   ├── BehaviorStatsEntity.kt
│   ├── ContextCacheDao.kt
│   ├── ContextCacheEntity.kt
│   ├── ContextEngine.kt
│   ├── UsageStatEntity.kt
│   └── UsageStatsDao.kt
│
├── learning
│   └── UILearningEngine.kt
│
├── overlay
│   ├── DebugOverlayService.kt
│   └── OverlayBridge.kt
│
├── planner
│   ├── ExecutionLogger.kt
│   ├── ExecutionRecord.kt
│   ├── ExperienceStore.kt
│   ├── PlanScorer.kt
│   └── ExecutionDao.kt (مُعرف داخل ExperienceStore.kt)
│
├── tools
│   ├── ToolDefinition.kt
│   ├── ToolExecutor.kt
│   ├── ToolRegistry.kt
│   └── ToolScanner.kt
│
└── world
    ├── ContextSnapshot.kt
    ├── RiskEstimator.kt
    ├── WorldState.kt
    └── WorldStateManager.kt
```

## مجلد `res`

يحتوي مجلد `res` على موارد التطبيق مثل ملفات التخطيط (layouts) والقيم (values) وملفات XML الخاصة بالخدمات.

```
res
├── layout
│   ├── chat_layout.xml
│   ├── item_chat_ai.xml
│   ├── item_chat_user.xml
│   └── overlay_layout.xml
├── values
│   ├── colors.xml
│   ├── strings.xml
│   └── themes.xml
└── xml
    ├── accessibility_service_config.xml
    └── airi_accessibility_config.xml
```

**ملاحظة:** تم استبعاد ملفات البناء (build files) ومجلدات Gradle من هذه الخارطة لتركيز على بنية الكود المصدر الأساسية.
