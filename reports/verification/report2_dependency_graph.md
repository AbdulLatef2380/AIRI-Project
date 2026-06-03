# REPORT 2 — Dependency Graph Report
*Generated: 2026-05-31 21:36:06*

## Symbol Index Summary

- Total symbols indexed: **1929**
- Classes: **458**
- Interfaces: **22**
- Objects: **168**
- Enums: **83**
- Sealed classes: **36**
- Top-level functions: **1106**


## Top 30 Most Referenced Symbols

| FQN | Caller Count |
|---|---|
| `com.airi.assistant.core.AiriLogger.i` | 112 |
| `com.airi.assistant.core.AiriLogger.e` | 99 |
| `com.airi.assistant.connector.get` | 87 |
| `com.airi.assistant.ui.theme.get` | 87 |
| `com.airi.assistant.ui.screens.companion` | 87 |
| `com.airi.assistant.accessibility.security.get` | 87 |
| `com.airi.assistant.agent.workspace.WorkspaceRegistry.get` | 87 |
| `com.airi.assistant.agent.workspace.get` | 87 |
| `com.airi.assistant.ai.companion` | 86 |
| `com.airi.assistant.ai.LlamaManager.companion` | 86 |
| `com.airi.assistant.ai.ModelType.companion` | 86 |
| `com.airi.assistant.terminal.companion` | 86 |
| `com.airi.assistant.telemetry.TelemetryConsentStore.companion` | 86 |
| `com.airi.assistant.auth.SecureStorage.companion` | 86 |
| `com.airi.assistant.voice.LiveVoiceSession.companion` | 86 |
| `com.airi.assistant.voice.companion` | 86 |
| `com.airi.assistant.app.AIRIApplication.companion` | 86 |
| `com.airi.assistant.profile.companion` | 86 |
| `com.airi.assistant.world.WorldStateManager.companion` | 86 |
| `com.airi.assistant.crash.companion` | 86 |
| `com.airi.assistant.billing.companion` | 86 |
| `com.airi.assistant.sync.companion` | 86 |
| `com.airi.assistant.tools.companion` | 86 |
| `com.airi.assistant.perf.companion` | 86 |
| `com.airi.assistant.memory.AiriDatabase.companion` | 86 |
| `com.airi.assistant.execution.companion` | 86 |
| `com.airi.assistant.ai.skills.SkillExecutor.companion` | 86 |
| `com.airi.assistant.ai.remote.RemoteModelExecutor.companion` | 86 |
| `com.airi.assistant.ai.agent.trace.AgentTraceManager.companion` | 86 |
| `com.airi.assistant.ai.agent.background.companion` | 86 |


## Package Dependency Matrix

| Package | Depends On |
|---|---|
| `com.airi.assistant` | `com.airi.assistant.domain.growth`, `com.airi.assistant.oauth`, `com.airi.assistant.system`, `com.airi.assistant.ui`, `com.airi.assistant.ui.theme`, `com.airi.assistant.voice` |
| `com.airi.assistant.accessibility.execution` | `com.airi.assistant.accessibility.security`, `com.airi.assistant.accessibility.service`, `com.airi.assistant.agent.execution.command`, `com.airi.assistant.agent.execution.node` |
| `com.airi.assistant.accessibility.scanner` | `com.airi.assistant.agent.planning` |
| `com.airi.assistant.accessibility.service` | `com.airi.assistant.accessibility.execution`, `com.airi.assistant.accessibility.security`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.core.intent`, `com.airi.assistant.memory`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.agent.execution.command` | `com.airi.assistant.accessibility.service`, `com.airi.assistant.agent.execution.context`, `com.airi.assistant.agent.execution.node`, `com.airi.assistant.agent.execution.validation`, `com.airi.assistant.agent.planning`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.core`, `com.airi.assistant.execution`, `com.airi.assistant.execution.prefs` |
| `com.airi.assistant.agent.execution.node` | `com.airi.assistant.agent.learning.reinforcement` |
| `com.airi.assistant.agent.governance` | `com.airi.assistant.ai`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.monetization` |
| `com.airi.assistant.agent.loop` | `com.airi.assistant.agent.loop.tool`, `com.airi.assistant.core`, `com.airi.assistant.execution`, `com.airi.assistant.ui.viewmodel` |
| `com.airi.assistant.agent.loop.tool` | `com.airi.assistant.accessibility.service`, `com.airi.assistant.agent.execution.command`, `com.airi.assistant.agent.execution.node`, `com.airi.assistant.memory.repository`, `com.airi.assistant.tools.execution`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.agent.observability` | `com.airi.assistant.agent.orchestrator`, `com.airi.assistant.agent.planning`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.voice` |
| `com.airi.assistant.agent.orchestrator` | `com.airi.assistant.agent.learning.reinforcement`, `com.airi.assistant.agent.planning`, `com.airi.assistant.agent.reflection`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.agent.workspace` |
| `com.airi.assistant.agent.planning` | `com.airi.assistant.core.intent` |
| `com.airi.assistant.agent.sandbox` | `com.airi.assistant.ui.activity` |
| `com.airi.assistant.agent.scheduler` | `com.airi.assistant.agent.subagent`, `com.airi.assistant.core`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.agent.subagent` | `com.airi.assistant.agent.learning.reinforcement` |
| `com.airi.assistant.agent.subagent.impl` | `com.airi.assistant.accessibility.execution`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.memory.repository`, `com.airi.assistant.tools.execution` |
| `com.airi.assistant.ai` | `com.airi.assistant.accessibility.service`, `com.airi.assistant.memory.entity`, `com.airi.assistant.memory.repository` |
| `com.airi.assistant.ai.agent` | `com.airi.assistant.ai.agent.trace`, `com.airi.assistant.ai.skills`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.ai.agent.background` | `com.airi.assistant.ai.intent`, `com.airi.assistant.ai.tools`, `com.airi.assistant.auth`, `com.airi.assistant.core`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.retention` |
| `com.airi.assistant.ai.prompt` | `com.airi.assistant.accessibility.security`, `com.airi.assistant.ai`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.ai.remote` | `com.airi.assistant.domain.logging` |
| `com.airi.assistant.ai.skills` | `com.airi.assistant.ai.skills.impl`, `com.airi.assistant.auth`, `com.airi.assistant.core`, `com.airi.assistant.domain.customskill` |
| `com.airi.assistant.ai.skills.impl` | `com.airi.assistant.ai.intent`, `com.airi.assistant.ai.skills`, `com.airi.assistant.ai.tools`, `com.airi.assistant.auth` |
| `com.airi.assistant.ai.tools` | `com.airi.assistant.ai.intent`, `com.airi.assistant.auth`, `com.airi.assistant.core`, `com.airi.assistant.domain.customskill`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.skill`, `com.airi.assistant.integrations.github`, `com.airi.assistant.integrations.telegram` |
| `com.airi.assistant.analytics` | `com.airi.assistant.domain.logging`, `com.airi.assistant.telemetry` |
| `com.airi.assistant.app` | `com.airi.assistant.agent.learning.reinforcement`, `com.airi.assistant.ai.remote`, `com.airi.assistant.analytics`, `com.airi.assistant.core`, `com.airi.assistant.crash`, `com.airi.assistant.domain.experiment`, `com.airi.assistant.domain.growth`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.retention`, `com.airi.assistant.integrity`, `com.airi.assistant.memory`, `com.airi.assistant.runtime.recovery`, `com.airi.assistant.sync`, `com.airi.assistant.system`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.auth.identity` | `com.airi.assistant.auth`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.billing` | `com.airi.assistant.analytics`, `com.airi.assistant.domain.monetization` |
| `com.airi.assistant.connector` | `com.airi.assistant.connector.api`, `com.airi.assistant.connector.legacy`, `com.airi.assistant.connector.local`, `com.airi.assistant.connector.mcp`, `com.airi.assistant.connector.system`, `com.airi.assistant.core.intent`, `com.airi.assistant.integration`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.connector.api` | `com.airi.assistant.connector` |
| `com.airi.assistant.connector.app` | `com.airi.assistant.connector`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.connector.legacy` | `com.airi.assistant.connector`, `com.airi.assistant.integration` |
| `com.airi.assistant.connector.local` | `com.airi.assistant.connector` |
| `com.airi.assistant.connector.mcp` | `com.airi.assistant.connector` |
| `com.airi.assistant.connector.system` | `com.airi.assistant.connector` |
| `com.airi.assistant.core` | `com.airi.assistant.accessibility.execution`, `com.airi.assistant.accessibility.service`, `com.airi.assistant.agent.learning`, `com.airi.assistant.agent.observability`, `com.airi.assistant.agent.orchestrator`, `com.airi.assistant.agent.scheduler`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.agent.subagent.impl`, `com.airi.assistant.agent.workspace`, `com.airi.assistant.auth`, `com.airi.assistant.auth.identity`, `com.airi.assistant.connector`, `com.airi.assistant.connector.api`, `com.airi.assistant.crash`, `com.airi.assistant.domain.auth`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.network`, `com.airi.assistant.domain.permission`, `com.airi.assistant.domain.policy`, `com.airi.assistant.domain.prompt`, `com.airi.assistant.domain.sharing`, `com.airi.assistant.domain.skill`, `com.airi.assistant.memory.rag`, `com.airi.assistant.memory.repository`, `com.airi.assistant.profile`, `com.airi.assistant.security`, `com.airi.assistant.sync`, `com.airi.assistant.telemetry`, `com.airi.assistant.ui.viewmodel`, `com.airi.assistant.voice` |
| `com.airi.assistant.core.debug` | `com.airi.assistant.core.analytics`, `com.airi.assistant.domain.diagnostics` |
| `com.airi.assistant.crash` | `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.network`, `com.airi.assistant.telemetry` |
| `com.airi.assistant.domain.auth` | `com.airi.assistant.domain.error`, `com.airi.assistant.domain.event` |
| `com.airi.assistant.domain.customskill` | `com.airi.assistant.ai.skills`, `com.airi.assistant.analytics`, `com.airi.assistant.core`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.diagnostics` | `com.airi.assistant.ai`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.verification` |
| `com.airi.assistant.domain.error` | `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.experiment` | `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.growth` | `com.airi.assistant.analytics`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.logging` | `com.airi.assistant` |
| `com.airi.assistant.domain.monetization` | `com.airi.assistant.analytics`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.network` | `com.airi.assistant.domain.error` |
| `com.airi.assistant.domain.policy` | `com.airi.assistant.agent.learning`, `com.airi.assistant.domain.auth`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.network`, `com.airi.assistant.domain.permission` |
| `com.airi.assistant.domain.prompt` | `com.airi.assistant.ai`, `com.airi.assistant.ai.skills`, `com.airi.assistant.ai.tools` |
| `com.airi.assistant.domain.retention` | `com.airi.assistant`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.domain.sharing` | `com.airi.assistant.domain.event`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.domain.skill` | `com.airi.assistant.agent.learning`, `com.airi.assistant.ai.intent`, `com.airi.assistant.ai.skills`, `com.airi.assistant.ai.tools`, `com.airi.assistant.analytics`, `com.airi.assistant.domain.customskill`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.policy` |
| `com.airi.assistant.execution` | `com.airi.assistant.ai`, `com.airi.assistant.core.debug`, `com.airi.assistant.execution.backend`, `com.airi.assistant.execution.diagnostics`, `com.airi.assistant.execution.prefs`, `com.airi.assistant.execution.privacy`, `com.airi.assistant.execution.router` |
| `com.airi.assistant.execution.accounting` | `com.airi.assistant.execution` |
| `com.airi.assistant.execution.backend` | `com.airi.assistant.ai`, `com.airi.assistant.core.debug`, `com.airi.assistant.execution`, `com.airi.assistant.execution.cloud`, `com.airi.assistant.execution.network`, `com.airi.assistant.execution.prefs` |
| `com.airi.assistant.execution.cloud` | `com.airi.assistant.ai`, `com.airi.assistant.ai.remote`, `com.airi.assistant.execution`, `com.airi.assistant.execution.security` |
| `com.airi.assistant.execution.diagnostics` | `com.airi.assistant.execution`, `com.airi.assistant.execution.cloud` |
| `com.airi.assistant.execution.network` | `com.airi.assistant.core.debug`, `com.airi.assistant.execution`, `com.airi.assistant.execution.prefs` |
| `com.airi.assistant.execution.prefs` | `com.airi.assistant.execution` |
| `com.airi.assistant.execution.privacy` | `com.airi.assistant.execution` |
| `com.airi.assistant.execution.router` | `com.airi.assistant.ai`, `com.airi.assistant.core.debug`, `com.airi.assistant.execution`, `com.airi.assistant.execution.backend`, `com.airi.assistant.execution.prefs` |
| `com.airi.assistant.execution.security` | `com.airi.assistant.auth`, `com.airi.assistant.execution` |
| `com.airi.assistant.integrations.github` | `com.airi.assistant.ai.tools`, `com.airi.assistant.auth` |
| `com.airi.assistant.integrations.google` | `com.airi.assistant.auth` |
| `com.airi.assistant.integrations.telegram` | `com.airi.assistant.ai.tools`, `com.airi.assistant.auth` |
| `com.airi.assistant.memory` | `com.airi.assistant.memory.dao`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.memory.dao` | `com.airi.assistant.memory.entity` |
| `com.airi.assistant.memory.embedding` | `com.airi.assistant.ai`, `com.airi.assistant.memory`, `com.airi.assistant.memory.entity`, `com.airi.assistant.memory.evolution` |
| `com.airi.assistant.memory.evolution` | `com.airi.assistant.memory.entity` |
| `com.airi.assistant.memory.rag` | `com.airi.assistant.memory.embedding`, `com.airi.assistant.memory.entity`, `com.airi.assistant.memory.repository` |
| `com.airi.assistant.memory.repository` | `com.airi.assistant.memory`, `com.airi.assistant.memory.dao`, `com.airi.assistant.memory.embedding`, `com.airi.assistant.memory.entity` |
| `com.airi.assistant.perf` | `com.airi.assistant.ai` |
| `com.airi.assistant.profile` | `com.airi.assistant.domain.logging` |
| `com.airi.assistant.security` | `com.airi.assistant.agent.subagent`, `com.airi.assistant.agent.workspace`, `com.airi.assistant.domain.logging`, `com.airi.assistant.telemetry`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.skills` | `com.airi.assistant.agent.sandbox`, `com.airi.assistant.ai.skills`, `com.airi.assistant.connector`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.sync` | `com.airi.assistant.core`, `com.airi.assistant.domain.logging`, `com.airi.assistant.profile` |
| `com.airi.assistant.system` | `com.airi.assistant.domain.logging` |
| `com.airi.assistant.telemetry` | `com.airi.assistant.analytics`, `com.airi.assistant.domain.logging` |
| `com.airi.assistant.terminal` | `com.airi.assistant.agent.sandbox`, `com.airi.assistant.security`, `com.airi.assistant.ui.activity` |
| `com.airi.assistant.ui` | `com.airi.assistant.analytics`, `com.airi.assistant.core`, `com.airi.assistant.domain.auth`, `com.airi.assistant.domain.experiment`, `com.airi.assistant.domain.growth`, `com.airi.assistant.ui.components`, `com.airi.assistant.ui.debug`, `com.airi.assistant.ui.plan`, `com.airi.assistant.ui.screens`, `com.airi.assistant.ui.theme`, `com.airi.assistant.ui.viewmodel` |
| `com.airi.assistant.ui.activity` | `com.airi.assistant.core`, `com.airi.assistant.domain.event`, `com.airi.assistant.ui.theme`, `com.airi.assistant.ui.viewmodel` |
| `com.airi.assistant.ui.components` | `com.airi.assistant`, `com.airi.assistant.analytics`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.ui.screens`, `com.airi.assistant.ui.theme` |
| `com.airi.assistant.ui.debug` | `com.airi.assistant.core.debug`, `com.airi.assistant.domain.verification` |
| `com.airi.assistant.ui.input` | `com.airi.assistant.ui.theme` |
| `com.airi.assistant.ui.plan` | `com.airi.assistant.core`, `com.airi.assistant.ui.theme`, `com.airi.assistant.ui.viewmodel` |
| `com.airi.assistant.ui.screens` | `com.airi.assistant`, `com.airi.assistant.agent.observability.AgentObservabilityHub`, `com.airi.assistant.agent.sandbox`, `com.airi.assistant.agent.scheduler`, `com.airi.assistant.ai`, `com.airi.assistant.ai.agent.trace`, `com.airi.assistant.ai.remote`, `com.airi.assistant.ai.skills`, `com.airi.assistant.analytics`, `com.airi.assistant.auth`, `com.airi.assistant.billing`, `com.airi.assistant.connector`, `com.airi.assistant.core`, `com.airi.assistant.core.debug`, `com.airi.assistant.domain.customskill`, `com.airi.assistant.domain.diagnostics`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.experiment`, `com.airi.assistant.domain.growth`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.policy`, `com.airi.assistant.domain.retention`, `com.airi.assistant.execution`, `com.airi.assistant.execution.accounting`, `com.airi.assistant.execution.cloud`, `com.airi.assistant.execution.diagnostics`, `com.airi.assistant.execution.prefs`, `com.airi.assistant.memory.dao`, `com.airi.assistant.memory.entity`, `com.airi.assistant.perf`, `com.airi.assistant.system`, `com.airi.assistant.terminal`, `com.airi.assistant.ui`, `com.airi.assistant.ui.activity`, `com.airi.assistant.ui.components`, `com.airi.assistant.ui.theme`, `com.airi.assistant.ui.util`, `com.airi.assistant.ui.viewmodel`, `com.airi.assistant.util`, `com.airi.assistant.voice`, `com.airi.assistant.workspace` |
| `com.airi.assistant.ui.text` | `com.airi.assistant.ui.util` |
| `com.airi.assistant.ui.viewmodel` | `com.airi.assistant`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.ai`, `com.airi.assistant.ai.agent.background`, `com.airi.assistant.ai.agent.trace`, `com.airi.assistant.ai.remote`, `com.airi.assistant.ai.skills`, `com.airi.assistant.analytics`, `com.airi.assistant.auth`, `com.airi.assistant.connector`, `com.airi.assistant.core`, `com.airi.assistant.core.debug`, `com.airi.assistant.domain.error`, `com.airi.assistant.domain.event`, `com.airi.assistant.domain.growth`, `com.airi.assistant.domain.logging`, `com.airi.assistant.domain.monetization`, `com.airi.assistant.domain.monetization.PaywallTriggerEngine`, `com.airi.assistant.domain.permission`, `com.airi.assistant.domain.retention`, `com.airi.assistant.domain.skill`, `com.airi.assistant.domain.skill.SkillService`, `com.airi.assistant.execution`, `com.airi.assistant.execution.accounting`, `com.airi.assistant.execution.backend`, `com.airi.assistant.execution.diagnostics`, `com.airi.assistant.execution.prefs`, `com.airi.assistant.execution.router`, `com.airi.assistant.execution.security`, `com.airi.assistant.integrations.github`, `com.airi.assistant.integrations.google`, `com.airi.assistant.integrations.telegram`, `com.airi.assistant.memory.dao`, `com.airi.assistant.memory.entity`, `com.airi.assistant.memory.repository`, `com.airi.assistant.tools`, `com.airi.assistant.voice` |
| `com.airi.assistant.util` | `com.airi.assistant.ui.viewmodel` |
| `com.airi.assistant.voice` | `com.airi.assistant`, `com.airi.assistant.agent.orchestrator`, `com.airi.assistant.agent.subagent`, `com.airi.assistant.core`, `com.airi.assistant.ui.activity`, `com.airi.assistant.voice.realtime` |
| `com.airi.assistant.workspace` | `com.airi.assistant.agent.sandbox`, `com.airi.assistant.ui.activity` |


## Gradle Issues

No issues found.


## Room Entity/DAO Issues

| Severity | Category | File | Line | Symbol | Message |
|---|---|---|---|---|---|
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `ChatMessage` | @Database references entity not annotated with @Entity: ChatMessage |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `ChatSession` | @Database references entity not annotated with @Entity: ChatSession |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `UserPreference` | @Database references entity not annotated with @Entity: UserPreference |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `BehaviorStatsEntity` | @Database references entity not annotated with @Entity: BehaviorStatsEntity |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `ContextCacheEntity` | @Database references entity not annotated with @Entity: ContextCacheEntity |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `UsageStatEntity` | @Database references entity not annotated with @Entity: UsageStatEntity |
| HIGH | ROOM_MISSING_ENTITY | `memory/AiriDatabase.kt` | 0 | `MessageEmbedding` | @Database references entity not annotated with @Entity: MessageEmbedding |


