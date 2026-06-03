# REPORT 1 — Compile-Risk Report
*Generated: 2026-05-31 21:36:06*

## Summary

- HIGH risk issues: **89**
- Unresolved imports (HIGH): **89**
- Deleted symbol references in live code: **0**
- Manifest component mismatches: **0**
- Duplicate symbol definitions: **56**


## Deleted Symbol References in Live Code

No issues found.


## Unresolved Imports

| Severity | Category | File | Line | Symbol | Message |
|---|---|---|---|---|---|
| HIGH | UNRESOLVED_IMPORT | `ui/AiriApp.kt` | 67 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 47 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 48 | `com.airi.assistant.ui.theme.DividerColor` | Cannot resolve import: com.airi.assistant.ui.theme.DividerColor |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 49 | `com.airi.assistant.ui.theme.NavBarBackground` | Cannot resolve import: com.airi.assistant.ui.theme.NavBarBackground |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 50 | `com.airi.assistant.ui.theme.NavIconActive` | Cannot resolve import: com.airi.assistant.ui.theme.NavIconActive |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 51 | `com.airi.assistant.ui.theme.NavIconInactive` | Cannot resolve import: com.airi.assistant.ui.theme.NavIconInactive |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriBottomNavBar.kt` | 52 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/components/GlassCard.kt` | 13 | `com.airi.assistant.ui.theme.GlassWhite` | Cannot resolve import: com.airi.assistant.ui.theme.GlassWhite |
| HIGH | UNRESOLVED_IMPORT | `ui/components/GlassCard.kt` | 14 | `com.airi.assistant.ui.theme.GlassWhiteBorder` | Cannot resolve import: com.airi.assistant.ui.theme.GlassWhiteBorder |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriAnimations.kt` | 17 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/components/AiriAnimations.kt` | 18 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/plan/AgentPlanCard.kt` | 20 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/plan/AgentPlanCard.kt` | 21 | `com.airi.assistant.ui.theme.SemanticError` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticError |
| HIGH | UNRESOLVED_IMPORT | `ui/plan/AgentPlanCard.kt` | 22 | `com.airi.assistant.ui.theme.SemanticSuccess` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticSuccess |
| HIGH | UNRESOLVED_IMPORT | `ui/plan/AgentPlanCard.kt` | 23 | `com.airi.assistant.ui.theme.SemanticWarn` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticWarn |
| HIGH | UNRESOLVED_IMPORT | `ui/input/PlusMenuSheet.kt` | 21 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/input/PlusMenuSheet.kt` | 22 | `com.airi.assistant.ui.theme.SurfaceFloating` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceFloating |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/GeneralSettingsScreen.kt` | 25 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/RuntimeDiagnosticsPanel.kt` | 24 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SkillManagerScreen.kt` | 36 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SkillManagerScreen.kt` | 37 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SkillManagerScreen.kt` | 38 | `com.airi.assistant.ui.theme.SemanticError` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticError |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SkillManagerScreen.kt` | 39 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ExecOriginBadge.kt` | 28 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 29 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 30 | `com.airi.assistant.ui.theme.SemanticError` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticError |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 31 | `com.airi.assistant.ui.theme.SemanticSuccess` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticSuccess |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 32 | `com.airi.assistant.ui.theme.SemanticWarn` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticWarn |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 33 | `com.airi.assistant.ui.theme.SurfaceFloating` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceFloating |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ObservabilityScreen.kt` | 34 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ProfileScreen.kt` | 26 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AppInfoScreen.kt` | 27 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentTraceDetailScreen.kt` | 25 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/MemoryScreen.kt` | 24 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentTasksScreen.kt` | 30 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentTasksScreen.kt` | 31 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentTasksScreen.kt` | 32 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ScreenHeader.kt` | 12 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentControlScreen.kt` | 21 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SkillBuilderScreen.kt` | 48 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AboutScreen.kt` | 18 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AboutScreen.kt` | 19 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/CloudModelStore.kt` | 29 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/AgentLogsScreen.kt` | 25 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/DebugPanelScreen.kt` | 22 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/HistoryScreen.kt` | 31 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/HistoryScreen.kt` | 32 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/HistoryScreen.kt` | 33 | `com.airi.assistant.ui.theme.DividerColor` | Cannot resolve import: com.airi.assistant.ui.theme.DividerColor |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/HistoryScreen.kt` | 34 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/HistoryScreen.kt` | 35 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 67 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 68 | `com.airi.assistant.ui.theme.CosmicAccentDark` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccentDark |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 69 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 70 | `com.airi.assistant.ui.theme.DividerColor` | Cannot resolve import: com.airi.assistant.ui.theme.DividerColor |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 71 | `com.airi.assistant.ui.theme.GlassPurple` | Cannot resolve import: com.airi.assistant.ui.theme.GlassPurple |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 72 | `com.airi.assistant.ui.theme.GlassPurpleBorder` | Cannot resolve import: com.airi.assistant.ui.theme.GlassPurpleBorder |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 73 | `com.airi.assistant.ui.theme.ModelPillBg` | Cannot resolve import: com.airi.assistant.ui.theme.ModelPillBg |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 74 | `com.airi.assistant.ui.theme.ModelPillBorder` | Cannot resolve import: com.airi.assistant.ui.theme.ModelPillBorder |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 75 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 76 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 78 | `com.airi.assistant.ui.theme.InputBarBackground` | Cannot resolve import: com.airi.assistant.ui.theme.InputBarBackground |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 90 | `com.airi.assistant.ui.theme.AiBubbleSurface` | Cannot resolve import: com.airi.assistant.ui.theme.AiBubbleSurface |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 91 | `com.airi.assistant.ui.theme.AiBubbleBorder` | Cannot resolve import: com.airi.assistant.ui.theme.AiBubbleBorder |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 92 | `com.airi.assistant.ui.theme.UserBubbleSurface` | Cannot resolve import: com.airi.assistant.ui.theme.UserBubbleSurface |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 93 | `com.airi.assistant.ui.theme.SemanticError` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticError |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ChatScreen.kt` | 94 | `com.airi.assistant.ui.theme.SemanticSuccess` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticSuccess |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 31 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 32 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 33 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 34 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 35 | `com.airi.assistant.ui.theme.SemanticSuccess` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticSuccess |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 36 | `com.airi.assistant.ui.theme.SemanticError` | Cannot resolve import: com.airi.assistant.ui.theme.SemanticError |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ConnectorsScreen.kt` | 37 | `com.airi.assistant.ui.theme.DividerColor` | Cannot resolve import: com.airi.assistant.ui.theme.DividerColor |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ExecutionModePanel.kt` | 29 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsScreen.kt` | 47 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsScreen.kt` | 48 | `com.airi.assistant.ui.theme.CosmicBlack` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicBlack |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsScreen.kt` | 49 | `com.airi.assistant.ui.theme.DividerColor` | Cannot resolve import: com.airi.assistant.ui.theme.DividerColor |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsScreen.kt` | 50 | `com.airi.assistant.ui.theme.SurfaceCard` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceCard |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsScreen.kt` | 51 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/CustomizationSettingsScreen.kt` | 21 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/PerformanceScreen.kt` | 37 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/OnboardingScreen.kt` | 39 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ReferralScreen.kt` | 31 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsComponents.kt` | 28 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/SettingsComponents.kt` | 29 | `com.airi.assistant.ui.theme.SurfaceRaised` | Cannot resolve import: com.airi.assistant.ui.theme.SurfaceRaised |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/WelcomeScreen.kt` | 15 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ModelSettingsScreen.kt` | 51 | `com.airi.assistant.ai.SUPPORTED_ARCHS` | Cannot resolve import: com.airi.assistant.ai.SUPPORTED_ARCHS |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ModelSettingsScreen.kt` | 56 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |
| HIGH | UNRESOLVED_IMPORT | `ui/screens/ExecDiagnosticsScreen.kt` | 38 | `com.airi.assistant.ui.theme.CosmicAccent` | Cannot resolve import: com.airi.assistant.ui.theme.CosmicAccent |


## Manifest Component Issues

No issues found.


## Duplicate Symbol Definitions

| FQN | File 1 | File 2 |
|---|---|---|
| `com.airi.assistant.ai.getAllModels` | `ai/ModelManager.kt` | `ai/ModelRegistry.kt` |
| `com.airi.assistant.ai.remove` | `ai/ModelManager.kt` | `ai/ModelRegistry.kt` |
| `com.airi.assistant.ai.companion` | `ai/SpeculativeManager.kt` | `ai/LlamaManager.kt` |
| `com.airi.assistant.ai.companion` | `ai/SpeculativeManager.kt` | `ai/ModelCapabilities.kt` |
| `com.airi.assistant.ai.companion` | `ai/SpeculativeManager.kt` | `ai/RuntimeSupervisor.kt` |
| `com.airi.assistant.ai.unload` | `ai/ModelManager.kt` | `ai/ModelLoader.kt` |
| `com.airi.assistant.voice.requestListen` | `voice/LiveVoiceService.kt` | `voice/LiveVoiceService.kt` |
| `com.airi.assistant.voice.requestStop` | `voice/LiveVoiceService.kt` | `voice/LiveVoiceService.kt` |
| `com.airi.assistant.voice.beginSpeaking` | `voice/LiveVoiceService.kt` | `voice/LiveVoiceService.kt` |
| `com.airi.assistant.voice.speakChunk` | `voice/LiveVoiceService.kt` | `voice/LiveVoiceService.kt` |
| `com.airi.assistant.voice.interruptSpeaking` | `voice/LiveVoiceService.kt` | `voice/LiveVoiceService.kt` |
| `com.airi.assistant.voice.start` | `voice/LiveVoiceService.kt` | `voice/VoskEngine.kt` |
| `com.airi.assistant.voice.stop` | `voice/LiveVoiceService.kt` | `voice/VoskEngine.kt` |
| `com.airi.assistant.voice.stop` | `voice/LiveVoiceService.kt` | `voice/IncrementalTtsEngine.kt` |
| `com.airi.assistant.voice.release` | `voice/VoskEngine.kt` | `voice/IncrementalTtsEngine.kt` |
| `com.airi.assistant.voice.release` | `voice/VoskEngine.kt` | `voice/VoiceInterruptController.kt` |
| `com.airi.assistant.voice.start` | `voice/LiveVoiceService.kt` | `voice/FullDuplexVadEngine.kt` |
| `com.airi.assistant.voice.stop` | `voice/LiveVoiceService.kt` | `voice/FullDuplexVadEngine.kt` |
| `com.airi.assistant.voice.start` | `voice/LiveVoiceService.kt` | `voice/DuplexConversationRuntime.kt` |
| `com.airi.assistant.voice.stop` | `voice/LiveVoiceService.kt` | `voice/DuplexConversationRuntime.kt` |
| `com.airi.assistant.voice.companion` | `voice/LiveVoiceService.kt` | `voice/HotwordService.kt` |
| `com.airi.assistant.voice.start` | `voice/LiveVoiceService.kt` | `voice/HotwordService.kt` |
| `com.airi.assistant.voice.stop` | `voice/LiveVoiceService.kt` | `voice/HotwordService.kt` |
| `com.airi.assistant.voice.companion` | `voice/LiveVoiceService.kt` | `voice/VoiceAgentRouter.kt` |
| `com.airi.assistant.crash.FirebaseCrashReporter.setKey` | `crash/FirebaseCrashReporter.kt` | `crash/FirebaseCrashReporter.kt` |
| `com.airi.assistant.crash.FirebaseCrashReporter.setKey` | `crash/FirebaseCrashReporter.kt` | `crash/FirebaseCrashReporter.kt` |
| `com.airi.assistant.crash.FirebaseCrashReporter.recordNonFatal` | `crash/FirebaseCrashReporter.kt` | `crash/FirebaseCrashReporter.kt` |
| `com.airi.assistant.crash.companion` | `crash/CrashReportStore.kt` | `crash/RuntimeHealthMonitor.kt` |
| `com.airi.assistant.tools.companion` | `tools/ModelDownloadService.kt` | `tools/ModelDownloadWorker.kt` |
| `com.airi.assistant.tools.cancel` | `tools/ModelDownloadService.kt` | `tools/ModelDownloadWorker.kt` |
| `com.airi.assistant.system.applyLocale` | `system/LanguageManager.kt` | `system/LanguageManager.kt` |
| `com.airi.assistant.execution.companion` | `execution/HybridOrchestrator.kt` | `execution/CapabilityProfile.kt` |
| `com.airi.assistant.ui.activity.AgentActivityBus.emit` | `ui/activity/AgentActivityBus.kt` | `ui/activity/AgentActivityBus.kt` |
| `com.airi.assistant.ui.screens.reload` | `ui/screens/SkillManagerScreen.kt` | `ui/screens/AgentTasksScreen.kt` |
| `com.airi.assistant.ui.screens.companion` | `ui/screens/SettingsScreen.kt` | `ui/screens/SettingsComponents.kt` |
| `com.airi.assistant.voice.realtime.Phase` | `voice/realtime/OpenAIRealtimeProvider.kt` | `voice/realtime/GeminiLiveProvider.kt` |
| `com.airi.assistant.voice.realtime.destroy` | `voice/realtime/OpenAIRealtimeProvider.kt` | `voice/realtime/GeminiLiveProvider.kt` |
| `com.airi.assistant.agent.sandbox.companion` | `agent/sandbox/SandboxSession.kt` | `agent/sandbox/SandboxExecutor.kt` |
| `com.airi.assistant.agent.scheduler.ScheduledAgentWorker` | `agent/scheduler/ScheduledJobOrchestrator.kt` | `agent/scheduler/ScheduledAgentWorker.kt` |
| `com.airi.assistant.agent.workspace.WorkspaceRegistry` | `agent/workspace/WorkspaceRegistry.kt` | `agent/workspace/SandboxWorkspace.kt` |
| `com.airi.assistant.agent.workspace.WorkspaceRegistry.get` | `agent/workspace/WorkspaceRegistry.kt` | `agent/workspace/SandboxWorkspace.kt` |
| `com.airi.assistant.agent.workspace.WorkspaceRegistry.release` | `agent/workspace/WorkspaceRegistry.kt` | `agent/workspace/SandboxWorkspace.kt` |
| `com.airi.assistant.agent.subagent.impl.companion` | `agent/subagent/impl/MemoryAgent.kt` | `agent/subagent/impl/CloudBrowserAgent.kt` |
| `com.airi.assistant.agent.subagent.impl.companion` | `agent/subagent/impl/MemoryAgent.kt` | `agent/subagent/impl/ProductivityAgent.kt` |
| `com.airi.assistant.agent.subagent.impl.companion` | `agent/subagent/impl/MemoryAgent.kt` | `agent/subagent/impl/AndroidAgent.kt` |
| `com.airi.assistant.agent.subagent.impl.companion` | `agent/subagent/impl/MemoryAgent.kt` | `agent/subagent/impl/ResearchAgent.kt` |
| `com.airi.assistant.connector.api.companion` | `connector/api/GeminiProvider.kt` | `connector/api/OpenAiProvider.kt` |
| `com.airi.assistant.connector.api.companion` | `connector/api/GeminiProvider.kt` | `connector/api/AnthropicProvider.kt` |
| `com.airi.assistant.connector.api.companion` | `connector/api/GeminiProvider.kt` | `connector/api/RemoteLlmConnector.kt` |
| `com.airi.assistant.tools.execution.summarize` | `tools/execution/NotesTool.kt` | `tools/execution/CalendarTool.kt` |
| `com.airi.assistant.domain.monetization.getPaywallMessage` | `domain/monetization/PaywallTriggerEngine.kt` | `domain/monetization/PaywallTriggerEngine.kt` |
| `com.airi.assistant.execution.backend.companion` | `execution/backend/CloudBackend.kt` | `execution/backend/LocalLlamaBackend.kt` |
| `com.airi.assistant.execution.router.companion` | `execution/router/DeviceSignals.kt` | `execution/router/RuntimeRouter.kt` |
| `com.airi.assistant.execution.cloud.companion` | `execution/cloud/OpenAIAdapter.kt` | `execution/cloud/GeminiAdapter.kt` |
| `com.airi.assistant.execution.cloud.companion` | `execution/cloud/OpenAIAdapter.kt` | `execution/cloud/AnthropicAdapter.kt` |
| `com.airi.assistant.execution.cloud.companion` | `execution/cloud/OpenAIAdapter.kt` | `execution/cloud/OpenRouterAdapter.kt` |


## Medium/Low Import Warnings

No issues found.


