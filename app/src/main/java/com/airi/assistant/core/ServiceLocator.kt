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
// ExecutionWatchdog — import preserved for future graph-native execution (Phase 9)
// import com.airi.assistant.crash.ExecutionWatchdog
import com.airi.assistant.crash.OrchestratorCrashReporter
import com.airi.assistant.crash.RuntimeHealthMonitor
import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.agent.observability.AgentObservabilityHub
// ExecutionGraphRuntime — preserved as class; not instantiated at startup (Phase 6 dead-code cleanup)
// import com.airi.assistant.agent.execution.runtime.ExecutionGraphRuntime
// SharedPreferencesSnapshotStore import removed (Phase 6) — no longer referenced by ServiceLocator
// import com.airi.assistant.agent.execution.runtime.SharedPreferencesSnapshotStore
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.scheduler.ScheduledJobOrchestrator
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.agent.subagent.impl.AndroidAgent
import com.airi.assistant.agent.subagent.impl.CloudBrowserAgent
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
import com.airi.assistant.domain.skill.SkillManagerBackend
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.memory.rag.RagRetriever
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.profile.HardwareProfiler
import com.airi.assistant.profile.UserProfileRepository
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ExecutionFirewall
import com.airi.assistant.security.ScopedPermissionRegistry
import com.airi.assistant.sync.CloudSyncCoordinator
import com.airi.assistant.telemetry.PrivacyTelemetryReporter
import com.airi.assistant.telemetry.TelemetryConsentStore
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
    // agentService removed — AgentService.handle() had 0 live callers after agent-first migration

    val skillService: SkillService by lazy {
        SkillService(requireContext())
    }

    val promptService: PromptService by lazy {
        PromptService(requireContext())
    }

    // ── Connectors layer ─────────────────────────────────────────────────────

    // ── Connector ecosystem (Phase 7) — declared BEFORE connectorRegistry ─────
    val connectorAuthManager: com.airi.assistant.connector.ConnectorAuthManager by lazy {
        com.airi.assistant.connector.ConnectorAuthManager(requireContext())
    }

    val connectorRegistry: ConnectorRegistry by lazy {
        val keys = secureStorage
        val llmProviders = listOf(
            OpenAiProvider    (keyProvider = { keys.getLlmKey("openai")    }),
            AnthropicProvider (keyProvider = { keys.getLlmKey("anthropic") }),
            GeminiProvider    (keyProvider = { keys.getLlmKey("gemini")    }),
        )
        ConnectorRegistry().also { reg ->
            ConnectorBootstrap.installDefaults(
                appContext   = requireContext(),
                registry     = reg,
                llmProviders = llmProviders,
            )
            // Register first-class connectors
            reg.register(com.airi.assistant.connector.app.GitHubConnector(connectorAuthManager))
        }
    }

    val connectorRuntimeManager: com.airi.assistant.connector.ConnectorRuntimeManager by lazy {
        com.airi.assistant.connector.ConnectorRuntimeManager(connectorRegistry)
    }

    val connectorHealthMonitor: com.airi.assistant.connector.ConnectorHealthMonitor by lazy {
        com.airi.assistant.connector.ConnectorHealthMonitor(connectorRegistry).also { it.start() }
    }

    // ── Sandbox (Phase 4) ─────────────────────────────────────────────────────

    val sandboxManager: com.airi.assistant.agent.sandbox.SandboxManager by lazy {
        com.airi.assistant.agent.sandbox.SandboxManager(requireContext())
    }

    // ── Phase P3: Workspace / Canvas runtime ─────────────────────────────────
    val artifactManager: com.airi.assistant.workspace.ArtifactManager by lazy {
        com.airi.assistant.workspace.ArtifactManager(requireContext())
    }

    val workspaceRuntime: com.airi.assistant.workspace.WorkspaceRuntime by lazy {
        com.airi.assistant.workspace.WorkspaceRuntime(
            context         = requireContext(),
            sandboxManager  = sandboxManager,
            artifactManager = artifactManager
        )
    }

    // ── Phase P5: Dynamic Skills runtime ──────────────────────────────────────
    val skillRuntime: com.airi.assistant.skills.SkillRuntime by lazy {
        val skillExec = com.airi.assistant.ai.skills.SkillExecutor(requireContext())
        com.airi.assistant.skills.SkillRuntime(
            context                 = requireContext(),
            skillRegistry           = skillExec.getRegistry(),
            orchestrator            = com.airi.assistant.ai.skills.AiriSkillOrchestrator,
            connectorRuntimeManager = connectorRuntimeManager,
            sandboxManager          = sandboxManager
        )
    }

    // ── Phase P6: Permission governance ───────────────────────────────────────
    val permissionGovernanceLayer: com.airi.assistant.security.PermissionGovernanceLayer by lazy {
        com.airi.assistant.security.PermissionGovernanceLayer(
            firewall  = executionFirewall,
            scopeReg  = scopedPermissionRegistry
        )
    }

    // ── Terminal runtime ───────────────────────────────────────────────────────
    val terminalRuntime: com.airi.assistant.terminal.TerminalRuntime by lazy {
        com.airi.assistant.terminal.TerminalRuntime(
            sandboxManager = sandboxManager,
            governance     = permissionGovernanceLayer
        )
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

    val observabilityHub: AgentObservabilityHub by lazy {
        AgentObservabilityHub()
    }

    val productionOrchestrator: ProductionAgentOrchestrator by lazy {
        ProductionAgentOrchestrator().also { orch ->
            orch.observabilityHub = observabilityHub
            observabilityHub.attachOrchestrator(orch)
        }
    }

    // ── REMOVED Phase 6 (dead runtime cleanup) ────────────────────────────────
    // executionSnapshotStore, executionGraphRuntime, executionWatchdog
    //
    // Reason: Phase 4 audit confirmed 0 functional callers for all three.
    // ExecutionWatchdog only polled executionGraphRuntime; neither was invoked
    // on any real execution path. Both classes are preserved on disk as
    // production infrastructure for the Phase 9 graph-native execution roadmap,
    // at which point the ServiceLocator will be restructured to provide them
    // with a real wiring path.
    //
    // Removing these 3 lazy properties saves ~25 ms of cold-start initializer
    // chain time and eliminates 3 unnecessary ServiceLocator dependency edges.

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

    // ── Skill Manager Backend ─────────────────────────────────────────────────

    val skillManagerBackend: SkillManagerBackend by lazy {
        SkillManagerBackend(requireContext())
    }

    /**
     * Initialize the sub-agent system with real tool-injected agents and
     * install the default permission set into the ScopedPermissionRegistry.
     *
     * Called once from Application.onCreate() after [init] has been called.
     * Includes all 9 new agent layers from the architecture expansion.
     */
    fun initSubAgentSystem() {
        val androidAgent = AndroidAgent(accessibilityExecutionEngine)
        val agents = listOf(
            // Real agents — verified non-delegation-shell implementations.
            // CodingAgent REMOVED (Phase 1): intercepted code/implement/write/create queries
            //   and returned "[CodingAgent delegated to LLM]" placeholder, blocking LLM response.
            // MediaGenerationAgent REMOVED (Phase 1): delegation shell, no real capability.
            // DocumentProcessorAgent REMOVED (Phase 1): delegation shell.
            // LocalBrowserOperator REMOVED (Phase 1): delegation shell, 0 real operations.
            ResearchAgent(searchTool),
            androidAgent,
            ProductivityAgent(calendarTool, alarmTool, notesTool),
            MemoryAgent(memoryManager),
            CloudBrowserAgent(requireContext())
        )
        SubAgentRegistry.initialize(agents)
        // Expose the Android agent for confirmation-gate injection by ChatViewModel.init
        _androidAgent = androidAgent
        scopedPermissionRegistry.installDefaults()
        observabilityHub.refreshRegistrySnapshot()
        runCatching { skillManagerBackend.reload() }
    }

    /** Set by [initSubAgentSystem]; consumed by ChatViewModel to inject the real confirmation gate. */
    @Volatile var _androidAgent: AndroidAgent? = null
}
