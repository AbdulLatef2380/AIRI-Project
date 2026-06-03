# REPORT 4 — Potential Runtime Crash Report
*Generated: 2026-05-31 21:36:06*

## Summary

- Force-unwrap (!!) instances: **23**
- Uncaught coroutine launches: **5**


## Force-Unwrap Instances (sample, first 50)

| Severity | Category | File | Line | Symbol | Message |
|---|---|---|---|---|---|
| MEDIUM | UNCAUGHT_COROUTINE_EXCEPTION | `voice/LiveVoiceService.kt` | 0 | `LiveVoiceService.kt` | File has 5 coroutine launches but 0 catch blocks |
| MEDIUM | UNCAUGHT_COROUTINE_EXCEPTION | `billing/BillingManager.kt` | 0 | `BillingManager.kt` | File has 4 coroutine launches but 0 catch blocks |
| MEDIUM | UNCAUGHT_COROUTINE_EXCEPTION | `ui/screens/ChatScreen.kt` | 0 | `ChatScreen.kt` | File has 12 coroutine launches but 0 catch blocks |
| MEDIUM | UNCAUGHT_COROUTINE_EXCEPTION | `agent/observability/AgentObservabilityHub.kt` | 0 | `AgentObservabilityHub.kt` | File has 13 coroutine launches but 0 catch blocks |
| MEDIUM | UNCAUGHT_COROUTINE_EXCEPTION | `memory/repository/MemoryManager.kt` | 0 | `MemoryManager.kt` | File has 4 coroutine launches but 0 catch blocks |
| LOW | FORCE_UNWRAP | `voice/VoskModelManager.kt` | 317 | `val rel = if (topPrefix!!.isNotEmpty() && name.startsWith(topPrefix!!))` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `voice/VoskModelManager.kt` | 318 | `name.substring(topPrefix!!.length)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `voice/IncrementalTtsEngine.kt` | 123 | `audioManager.requestAudioFocus(focusRequest!!)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `world/WorldStateManager.kt` | 108 | `beforeState = lastState!!,` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `workspace/WorkspaceRuntime.kt` | 77 | `sessions[sessionId] = _activeSession.value!!` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `core/VoiceManager.kt` | 301 | `tts!!.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `core/VoiceManager.kt` | 345 | `tts!!.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `core/VoiceManager.kt` | 379 | `tts!!.speak(content, TextToSpeech.QUEUE_ADD, null, utteranceId)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/plan/TaskExecutionTracker.kt` | 52 | `val updated = stepRegistry[placeholder]!!.copy(id = nodeId, label = nodeLabel.ta` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/screens/AgentTraceDetailScreen.kt` | 44 | `val t = trace!!` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/screens/PerformanceScreen.kt` | 445 | `val sz   = runCatching { java.io.File(draftPath!!).length() }.getOrDefault(0L)` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/screens/PerformanceScreen.kt` | 448 | `loadStatus != null -> stringResource(R.string.spec_status_load_failed, loadStatu` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/screens/VoiceSettingsScreen.kt` | 128 | `progress = (downloadProgress!! / 100f),` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `ui/screens/VoiceSettingsScreen.kt` | 134 | `"جارٍ التنزيل… ${downloadProgress!!}%",` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `accessibility/service/IntentDetector.kt` | 25 | `scores[IntentType.DEBUG_ERROR] = scores[IntentType.DEBUG_ERROR]!! + 4` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `accessibility/service/IntentDetector.kt` | 29 | `scores[IntentType.CODE_ANALYSIS] = scores[IntentType.CODE_ANALYSIS]!! + 3` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `accessibility/service/IntentDetector.kt` | 33 | `scores[IntentType.SUMMARIZE] = scores[IntentType.SUMMARIZE]!! + 2` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `agent/orchestrator/ProductionAgentOrchestrator.kt` | 439 | `val error = taskError!!` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `agent/subagent/impl/AndroidAgent.kt` | 201 | `emit(AgentEvent.Failed(reason = errorReason!!, recoverable = true))` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `domain/monetization/PaywallTriggerEngine.kt` | 53 | `lastShownMs.set(prefs!!.getLong(KEY_LAST_SHOWN, 0L))` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `execution/cloud/OpenAIAdapter.kt` | 112 | `val raw = line!!.trim()` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `execution/cloud/GeminiAdapter.kt` | 106 | `val raw = line!!.trim()` | Force-unwrap (!!) may cause NullPointerException |
| LOW | FORCE_UNWRAP | `execution/cloud/AnthropicAdapter.kt` | 104 | `val raw = line!!.trim()` | Force-unwrap (!!) may cause NullPointerException |


