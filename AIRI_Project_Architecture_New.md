# خارطة بنية مشروع AIRI الجديدة (Agent + Clean Architecture)

توضح هذه الوثيقة الهيكل المحدث لمشروع AIRI بعد عملية إعادة التنظيم (Refactoring) لضمان استقرار البنية وتوزيع المسؤوليات بشكل صحيح.

```
com.airi.assistant
│
├── app (طبقة التطبيق والتشغيل)
│   ├── AIRIApplication.kt
│   └── MainActivity.kt
│
├── core (المحرك الأساسي والمنطق المشترك)
│   ├── AiriCore.kt
│   ├── UnifiedCognitiveLoop.kt
│   ├── IntentRouter.kt
│   ├── AiriPersona.kt
│   ├── AuditManager.kt
│   ├── DialogueRhythmEngine.kt
│   ├── EmotionEngine.kt
│   ├── EthicalMemoryController.kt
│   ├── GracefulDetachmentProtocol.kt
│   ├── GuardianEngine.kt
│   ├── ModelDownloadManager.kt
│   ├── ModelDownloadService.kt
│   ├── PatternAggregator.kt
│   ├── PolicyEngine.kt
│   ├── RelationshipBoundaryPolicy.kt
│   ├── SensoryBudgetManager.kt
│   ├── SystemControl.kt
│   └── VoiceManager.kt
│
├── ai (طبقة الذكاء الاصطناعي والنماذج)
│   ├── model
│   │   ├── LlamaManager.kt
│   │   └── LlamaNative.kt
│   └── prompt
│       └── PromptBuilder.kt
│
├── memory (طبقة الذاكرة والبيانات)
│   ├── MemoryEntities.kt
│   ├── MemoryDao.kt
│   ├── MemoryManager.kt
│   ├── AiriDatabase.kt
│   ├── AppDatabase.kt
│   ├── BehaviorStatsDao.kt
│   ├── BehaviorStatsEntity.kt
│   ├── ContextCacheDao.kt
│   ├── ContextCacheEntity.kt
│   ├── ContextEngine.kt
│   ├── UsageStatEntity.kt
│   └── UsageStatsDao.kt
│
├── agent (طبقة الوكيل الذكي - التخطيط والقرار)
│   ├── ActionPlan.kt
│   ├── ActionPlanner.kt
│   ├── AdaptiveBehaviorEngine.kt
│   ├── AdaptiveDecisionEngine.kt
│   ├── AgentGoal.kt
│   ├── AiriBrainController.kt
│   ├── AiriIntent.kt
│   ├── BehaviorPolicy.kt
│   ├── BrainIO.kt
│   ├── BrainManager.kt
│   ├── ConfidenceScorer.kt
│   ├── ExecutionLogger.kt
│   ├── ExecutionRecord.kt
│   ├── ExecutionResult.kt
│   ├── ExperienceStore.kt
│   ├── GoalExecutor.kt
│   ├── IntentEngine.kt
│   ├── IntentType.kt
│   ├── InteractionTracker.kt
│   ├── PlanGenerator.kt
│   ├── PlanScorer.kt
│   ├── PlanStep.kt
│   ├── PlanValidator.kt
│   ├── RecoveryManager.kt
│   ├── SuggestionScoreEngine.kt
│   ├── UILearningEngine.kt
│   ├── UIMemory.kt
│   ├── ValidationException.kt
│   ├── command
│   ├── context
│   ├── decision
│   ├── node
│   ├── reinforcement
│   ├── runtime
│   ├── task
│   └── validation
│
├── accessibility (طبقة التفاعل مع النظام وقراءة الشاشة)
│   ├── service
│   │   ├── AIRIAccessibilityService.kt
│   │   ├── BehaviorEngine.kt
│   │   ├── BehaviorMemory.kt
│   │   ├── ContextActionEngine.kt
│   │   ├── ContextClassifier.kt
│   │   ├── ContextIntelligence.kt
│   │   ├── IntentDetector.kt
│   │   ├── IntentPredictor.kt
│   │   ├── IntentType.kt
│   │   ├── OverlayBridge.kt
│   │   ├── ScreenContextHolder.kt
│   │   ├── SessionMemory.kt
│   │   ├── SmartActionEngine.kt
│   │   └── SuggestionEngine.kt
│   ├── scanner
│   │   ├── NodeFinder.kt
│   │   └── UITreeScanner.kt
│   └── executor
│       └── ActionExecutor.kt
│
├── world (طبقة فهم البيئة المحيطة والسياق)
│   ├── WorldState.kt
│   ├── WorldStateManager.kt
│   ├── ContextSnapshot.kt
│   ├── RiskEstimator.kt
│   └── ScreenHasher.kt
│
├── tools (طبقة الأدوات والخدمات الخارجية)
│   ├── N8nIntegration.kt
│   ├── ToolDefinition.kt
│   ├── ToolExecutor.kt
│   ├── ToolRegistry.kt
│   └── ToolScanner.kt
│
├── ui (طبقة واجهة المستخدم والخدمات المرئية)
│   ├── OverlayService.kt
│   ├── AvatarView.kt
│   ├── ChatAdapter.kt
│   ├── ChatModel.kt
│   └── overlay (DebugOverlayService, OverlayBridge)
```

## أهم التغييرات:
1.  **استقرار البنية:** تم نقل الملفات من الحزمة الرئيسية المزدحمة إلى مجلدات متخصصة.
2.  **تحديث المراجع:** تم تحديث `package declaration` في جميع ملفات Kotlin لتتوافق مع المسارات الجديدة.
3.  **تحديث Manifest:** تم تحديث ملف `AndroidManifest.xml` ليعكس المسارات الجديدة للـ Activities والـ Services لضمان عمل التطبيق.
4.  **توزيع المسؤوليات:** أصبح كل جزء من النظام (AI, Memory, Agent, World) معزولاً في طبقته الخاصة.
