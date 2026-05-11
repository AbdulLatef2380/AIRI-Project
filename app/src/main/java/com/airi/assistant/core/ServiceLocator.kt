package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.auth.identity.BiometricGatekeeper
import com.airi.assistant.auth.identity.DeviceBindingService
import com.airi.assistant.auth.identity.SessionManager
import com.airi.assistant.connector.AgentRouter
import com.airi.assistant.connector.ConnectorBootstrap
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.api.AnthropicProvider
import com.airi.assistant.connector.api.GeminiProvider
import com.airi.assistant.connector.api.OpenAiProvider
import com.airi.assistant.crash.CrashReportStore
import com.airi.assistant.crash.ExecutionWatchdog
import com.airi.assistant.crash.OrchestratorCrashReporter
import com.airi.assistant.crash.RuntimeHealthMonitor
import com.airi.assistant.domain.agent.AgentService
import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.governance.ModelGovernanceEngine
import com.airi.assistant.agent.observability.AgentObservabilityHub
import com.airi.assistant.agent.execution.runtime.ExecutionGraphRuntime
import com.airi.assistant.agent.execution.runtime.SharedPreferencesSnapshotStore
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.planning.CoTEngine
import com.airi.assistant.agent.planning.ReActPlanner
import com.airi.assistant.agent.scheduler.ScheduledJobOrchestrator
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.agent.subagent.impl.AndroidAgent
import com.airi.assistant.agent.subagent.impl.CloudBrowserAgent
import com.airi.assistant.agent.subagent.impl.CodingAgent
import com.airi.assistant.agent.subagent.impl.DocumentProcessorAgent
import com.airi.assistant.agent.subagent.impl.LocalBrowserOperator
import com.airi.assistant.agent.subagent.impl.MediaGenerationAgent
import com.airi.assistant.agent.subagent.impl.MemoryAgent
import com.airi.assistant.agent.subagent.impl.ProductivityAgent
import com.airi.assistant.agent.subagent.impl.ResearchAgent
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.domain.event.AgentEventStream
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.domain.monetization.CreditMeteringEngine
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.network.NetworkService
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.domain.prompt.PromptService
import com.airi.assistant.domain.sharing.ChatSharingService
import com.airi.assistant.agent.learning.SkillOutcomeScorer
import com.airi.assistant.agent.workspace.WorkspaceRegistry
import com.airi.assistant.domain.policy.UnifiedPolicyGate
import com.airi.assistant.domain.diagnostics.DiagnosticsEngine
import com.airi.assistant.domain.skill.SkillManagerBackend
import com.airi.assistant.domain.skill.SkillOrchestrator
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.memory.rag.RagRetriever
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.profile.HardwareProfiler
import com.airi.assistant.profile.UserProfileRepository
import com.airi.assistant.ai.ContextPressureManager
import com.airi.assistant.ai.InferenceWatchdog
import com.airi.assistant.ai.InternalModelExtractor
import com.airi.assistant.core.runtime.AgentContinuationEngine
import com.airi.assistant.core.runtime.AutonomousRuntimeManager
import com.airi.assistant.core.runtime.TaskCheckpointStore
import com.airi.assistant.crash.StressTestRunner
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ExecutionFirewall
import com.airi.assistant.security.SandboxedProcessManager
import com.airi.assistant.security.ScopedPermissionRegistry
import com.airi.assistant.security.SecureExecutionPolicy
import com.airi.assistant.sync.CloudSyncCoordinator
import com.airi.assistant.telemetry.PrivacyTelemetryReporter
import com.airi.assistant.telemetry.TelemetryConsentStore
import com.airi.assistant.voice.EmbeddedVoiceRuntime
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object ServiceLocator {

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    var context: Context?
        get() = appContext
        set(value) {
            if (value != null) appContext = value.applicationContext
        }

    private fun requireContext(): Context =
        requireNotNull(appContext) { "ServiceLocator.init() was not called before use" }

    // ── Infrastructure ────────────────────────────────────────────────────────

    val networkService: NetworkService by lazy {
        NetworkService(requireContext())
    }

    val authService: AuthService by lazy {
        AuthService()
    }

    val permissionService: PermissionService by lazy {
        PermissionService(requireContext())
    }

    val policyEngine get() = PolicyEngine
    val errorHandler get() = AppErrorHandler

    // ── Identity Layer ────────────────────────────────────────────────────────

    val secureStorage: SecureStorage by lazy {
        SecureStorage(requireContext())
    }

    val deviceBindingService: DeviceBindingService by lazy {
        DeviceBindingService(requireContext())
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(requireContext(), deviceBindingService)
    }

    val biometricGatekeeper get() = BiometricGatekeeper

    // ── User Profile ──────────────────────────────────────────────────────────

    val userProfileRepository: UserProfileRepository by lazy {
        UserProfileRepository(requireContext())
    }

    val hardwareProfiler: HardwareProfiler by lazy {
        HardwareProfiler(requireContext())
    }

    // ── Monetization ──────────────────────────────────────────────────────────

    val subscriptionManager: SubscriptionManager by lazy {
        SubscriptionManager(requireContext())
    }

    // ── Privacy-Aware Telemetry ───────────────────────────────────────────────

    val telemetryConsentStore: TelemetryConsentStore by lazy {
        TelemetryConsentStore(requireContext())
    }

    val privacyTelemetryReporter: PrivacyTelemetryReporter by lazy {
        PrivacyTelemetryReporter(telemetryConsentStore)
    }

    // ── Security Stack ────────────────────────────────────────────────────────

    val scopedPermissionRegistry: ScopedPermissionRegistry by lazy {
        ScopedPermissionRegistry()
    }

    val executionFirewall: ExecutionFirewall by lazy {
        ExecutionFirewall(scopedPermissionRegistry)
    }

    val agentSandbox: AgentSandbox by lazy {
        AgentSandbox(executionFirewall, scopedPermissionRegistry, privacyTelemetryReporter)
    }

    // ── Phase 2: Sandboxed Process Management ─────────────────────────────────

    val sandboxedProcessManager: SandboxedProcessManager by lazy {
        SandboxedProcessManager(maxParallel = 4, defaultTimeout = 15_000L)
    }

    val secureExecutionPolicy: SecureExecutionPolicy by lazy {
        SecureExecutionPolicy(
            permissionRegistry = scopedPermissionRegistry,
            allowedRiskLevel   = SecureExecutionPolicy.RiskLevel.MEDIUM,
            maxCallsPerMinute  = 60
        )
    }

    // ── Crash / Runtime Reporting ─────────────────────────────────────────────

    val crashReportStore: CrashReportStore by lazy {
        CrashReportStore(requireContext())
    }

    val crashReporter: OrchestratorCrashReporter by lazy {
        OrchestratorCrashReporter(crashReportStore, privacyTelemetryReporter)
    }

    val runtimeHealthMonitor: RuntimeHealthMonitor by lazy {
        RuntimeHealthMonitor(requireContext(), crashReporter, networkService)
    }

    // ── Event / Observability ─────────────────────────────────────────────────

    val executionHistoryStore: ExecutionHistoryStore by lazy {
        ExecutionHistoryStore(requireContext())
    }

    val agentEventStream: AgentEventStream = AgentEventStream

    // ── Domain: Execution ─────────────────────────────────────────────────────

    val agentService: AgentService by lazy {
        AgentService(requireContext())
    }

    val skillService: SkillService by lazy {
        SkillService(requireContext())
    }

    // ── Skill Orchestrator (Phase 6) ──────────────────────────────────────────

    /**
     * Production-grade skill router. Sits above [skillService] and provides
     * category routing, priority ranking, retry logic, timeout, and fallback.
     * Consumed by ChatViewModel and any agent layer that needs to dispatch a
     * user intent to the most appropriate skill before falling back to LLM.
     */
    val skillOrchestrator: SkillOrchestrator by lazy {
        SkillOrchestrator(skillService)
    }

    // ── Diagnostics Engine (Phase 9) ─────────────────────────────────────────

    /**
     * Continuous background health monitor. Exposes a [StateFlow<HealthSnapshot>]
     * that the debug screen and agent self-diagnostics consume. Started lazily
     * on first access; call [diagnosticsEngine.start()] from Application.onCreate
     * or wherever diagnostics should begin running.
     */
    val diagnosticsEngine: DiagnosticsEngine by lazy {
        DiagnosticsEngine(requireContext())
    }

    val promptService: PromptService by lazy {
        PromptService(requireContext())
    }

    // ── Connectors layer ─────────────────────────────────────────────────────

    val connectorRegistry: ConnectorRegistry by lazy {
        val keys = secureStorage
        val llmProviders = listOf(
            OpenAiProvider    (keyProvider = { keys.getLlmKey("openai")    }),
            AnthropicProvider (keyProvider = { keys.getLlmKey("anthropic") }),
            GeminiProvider    (keyProvider = { keys.getLlmKey("gemini")    }),
        )
        ConnectorRegistry().also { reg ->
            ConnectorBootstrap.installDefaults(
                appContext          = requireContext(),
                registry            = reg,
                llmProviders        = llmProviders,
                ragRetriever        = ragRetriever,
                memoryManager       = memoryManager,
                accessibilityEngine = accessibilityExecutionEngine,
            )
        }
    }

    val agentRouter: AgentRouter by lazy {
        AgentRouter(connectorRegistry)
    }

    // ── Voice transcript bus ──────────────────────────────────────────────────

    val voiceTranscriptBus: MutableSharedFlow<String> = MutableSharedFlow(
        extraBufferCapacity = 4,
        onBufferOverflow    = BufferOverflow.DROP_OLDEST
    )

    // ── AIRI Ascension: Sub-Agent Layer ───────────────────────────────────────

    val durableTaskManager: DurableTaskManager by lazy {
        DurableTaskManager(requireContext())
    }

    val observabilityHub: AgentObservabilityHub by lazy {
        AgentObservabilityHub()
    }

    val productionOrchestrator: ProductionAgentOrchestrator by lazy {
        ProductionAgentOrchestrator().also { orch ->
            orch.observabilityHub = observabilityHub
            observabilityHub.attachOrchestrator(orch)
        }
    }

    val executionSnapshotStore: SharedPreferencesSnapshotStore by lazy {
        SharedPreferencesSnapshotStore(requireContext())
    }

    val executionGraphRuntime: ExecutionGraphRuntime by lazy {
        ExecutionGraphRuntime(
            orchestrator       = productionOrchestrator,
            durableTaskManager = durableTaskManager,
            snapshotStore      = executionSnapshotStore
        )
    }

    val executionWatchdog: ExecutionWatchdog by lazy {
        ExecutionWatchdog(
            runtime         = executionGraphRuntime,
            crashReporter   = crashReporter,
            telemetry       = privacyTelemetryReporter,
            autoCancelStuck = false
        )
    }

    // ── Phase 1: Autonomous Runtime ───────────────────────────────────────────

    val taskCheckpointStore: TaskCheckpointStore by lazy {
        TaskCheckpointStore(requireContext())
    }

    val agentContinuationEngine: AgentContinuationEngine by lazy {
        AgentContinuationEngine(taskCheckpointStore)
    }

    val autonomousRuntimeManager: AutonomousRuntimeManager by lazy {
        AutonomousRuntimeManager(
            checkpointStore    = taskCheckpointStore,
            continuationEngine = agentContinuationEngine,
            orchestrator       = productionOrchestrator
        )
    }

    // ── Phase 4: Performance & Stability ─────────────────────────────────────

    val inferenceWatchdog: InferenceWatchdog by lazy {
        InferenceWatchdog(
            crashReporter     = crashReporter,
            telemetry         = privacyTelemetryReporter,
            autoCancelOnStuck = false
        )
    }

    val contextPressureManager: ContextPressureManager by lazy {
        ContextPressureManager(contextWindowSize = ContextPressureManager.DEFAULT_CONTEXT_WINDOW)
    }

    val stressTestRunner: StressTestRunner by lazy {
        StressTestRunner(
            orchestrator  = productionOrchestrator,
            crashReporter = crashReporter
        )
    }

    // ── Phase 5: Voice System ─────────────────────────────────────────────────

    val embeddedVoiceRuntime: EmbeddedVoiceRuntime by lazy {
        EmbeddedVoiceRuntime(
            appContext = requireContext()
        )
    }

    val internalModelExtractor: InternalModelExtractor by lazy {
        InternalModelExtractor(requireContext())
    }

    // ── Cloud Sync ────────────────────────────────────────────────────────────

    val cloudSyncCoordinator: CloudSyncCoordinator by lazy {
        CloudSyncCoordinator(userProfileRepository)
    }

    // ── Tool Execution Layer ──────────────────────────────────────────────────

    val calendarTool: com.airi.assistant.tools.execution.CalendarTool by lazy {
        com.airi.assistant.tools.execution.CalendarTool(requireContext())
    }

    val alarmTool: com.airi.assistant.tools.execution.AlarmTool by lazy {
        com.airi.assistant.tools.execution.AlarmTool(requireContext())
    }

    val notificationTool: com.airi.assistant.tools.execution.NotificationTool by lazy {
        com.airi.assistant.tools.execution.NotificationTool(requireContext())
    }

    val searchTool: com.airi.assistant.tools.execution.SearchTool by lazy {
        com.airi.assistant.tools.execution.SearchTool(requireContext())
    }

    val notesTool: com.airi.assistant.tools.execution.NotesTool by lazy {
        com.airi.assistant.tools.execution.NotesTool(requireContext())
    }

    // ── Memory Layer ──────────────────────────────────────────────────────────

    val memoryManager: MemoryManager by lazy {
        MemoryManager(requireContext())
    }

    // ── Accessibility Execution Engine ────────────────────────────────────────

    val accessibilityExecutionEngine: AccessibilityExecutionEngine by lazy {
        AccessibilityExecutionEngine()
    }

    // ── CoT / ReAct Planning ─────────────────────────────────────────────────

    val cotEngine: CoTEngine by lazy {
        CoTEngine()
    }

    val reActPlanner: ReActPlanner by lazy {
        ReActPlanner(cotEngine = cotEngine, maxSteps = 6)
    }

    // ── Scheduled Job Orchestration ──────────────────────────────────────────

    val scheduledJobOrchestrator: ScheduledJobOrchestrator by lazy {
        ScheduledJobOrchestrator(requireContext())
    }

    // ── Credit / Consumption Metering ────────────────────────────────────────

    val creditMeteringEngine: CreditMeteringEngine by lazy {
        CreditMeteringEngine(requireContext(), subscriptionManager)
    }

    // ── Self-Improvement + Unified Policy ────────────────────────────────────

    val skillOutcomeScorer: SkillOutcomeScorer by lazy {
        SkillOutcomeScorer.getInstance(requireContext())
    }

    /** Singleton object — exposed here for uniform access via ServiceLocator. */
    val unifiedPolicyGate get() = UnifiedPolicyGate

    /** Singleton object — exposes the global WorkspaceRegistry. */
    val workspaceRegistry get() = WorkspaceRegistry

    // ── Chat Sharing ──────────────────────────────────────────────────────────

    val chatSharingService: ChatSharingService by lazy {
        ChatSharingService(requireContext())
    }

    // ── RAG Retriever ─────────────────────────────────────────────────────────

    val ragRetriever: RagRetriever by lazy {
        RagRetriever(memoryManager)
    }

    // ── Phase 4 Memory Stores ─────────────────────────────────────────────────

    val episodicMemoryStore: com.airi.assistant.memory.EpisodicMemoryStore by lazy {
        com.airi.assistant.memory.EpisodicMemoryStore(requireContext())
    }

    val errorMemoryStore: com.airi.assistant.memory.ErrorMemoryStore by lazy {
        com.airi.assistant.memory.ErrorMemoryStore(requireContext())
    }

    val userPreferenceMemory: com.airi.assistant.memory.UserPreferenceMemory by lazy {
        com.airi.assistant.memory.UserPreferenceMemory(requireContext())
    }

    // ── Phase 7 Provider Manager ──────────────────────────────────────────────

    val providerManager: com.airi.assistant.execution.ProviderManager by lazy {
        com.airi.assistant.execution.ProviderManager()
    }

    // ── Model Governance Engine ───────────────────────────────────────────────

    val modelGovernanceEngine: ModelGovernanceEngine by lazy {
        ModelGovernanceEngine(requireContext(), subscriptionManager)
    }

    // ── Skill Manager Backend ─────────────────────────────────────────────────

    val skillManagerBackend: SkillManagerBackend by lazy {
        SkillManagerBackend(requireContext())
    }

    // ── Closed-Loop Adaptive Intelligence ────────────────────────────────────

    /**
     * Singleton [PlannerAdaptationEngine] — survives across [UnifiedCognitiveLoop]
     * instantiations. All accumulated learning (failure rates, agent trust,
     * strategy scores, avoided actions) is persisted here and feeds back into
     * every future plan generation call.
     */
    val plannerAdaptationEngine: com.airi.assistant.agent.adaptation.PlannerAdaptationEngine by lazy {
        com.airi.assistant.agent.adaptation.PlannerAdaptationEngine(requireContext())
    }

    /**
     * Initialize the sub-agent system with real tool-injected agents and
     * install the default permission set into the ScopedPermissionRegistry.
     *
     * Called once from Application.onCreate() after [init] has been called.
     * Includes all 9 new agent layers from the architecture expansion.
     */
    fun initSubAgentSystem() {
        val agents = listOf(
            // ── Core agents (existing) ──────────────────────────────────────
            CodingAgent(),
            ResearchAgent(searchTool),
            AndroidAgent(accessibilityExecutionEngine),
            ProductivityAgent(calendarTool, alarmTool, notesTool),
            MemoryAgent(memoryManager),
            // ── New agent layers (architecture expansion) ───────────────────
            CloudBrowserAgent(requireContext()),
            LocalBrowserOperator(requireContext()),
            MediaGenerationAgent(requireContext()),
            DocumentProcessorAgent(requireContext())
        )
        SubAgentRegistry.initialize(agents)
        scopedPermissionRegistry.installDefaults()
        observabilityHub.refreshRegistrySnapshot()

        // Seed skill manager with any bundled/installed dynamic skills
        runCatching { skillManagerBackend.reload() }
    }
}
