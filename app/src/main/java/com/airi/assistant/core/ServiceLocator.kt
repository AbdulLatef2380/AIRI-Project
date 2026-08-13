package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.execution.security.SecureApiKeyStore
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
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.learning.SkillOutcomeScorer
import com.airi.assistant.agent.workspace.WorkspaceRegistry
import com.airi.assistant.domain.policy.UnifiedPolicyGate
import com.airi.assistant.domain.skill.SkillManagerBackend
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.domain.auth.DataDeletionCoordinator
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.memory.rag.RagRetriever
import com.airi.assistant.memory.repository.MemoryManager
import com.airi.assistant.memory.repository.StorageRepository
import com.airi.assistant.profile.HardwareProfiler
import com.airi.assistant.profile.UserProfileRepository
import com.airi.assistant.security.AgentSandbox
import com.airi.assistant.security.ExecutionFirewall
import com.airi.assistant.security.ScopedPermissionRegistry
import com.airi.assistant.integrations.google.GoogleAuthService
import com.airi.assistant.runtime.profiler.RuntimeProfiler
import com.airi.assistant.sync.CloudSyncCoordinator
import com.airi.assistant.telemetry.PrivacyTelemetryReporter
import com.airi.assistant.telemetry.TelemetryConsentStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
        
        AuthService(auditRepository = auditRepository)
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

    val secureApiKeyStore: SecureApiKeyStore by lazy {
        SecureApiKeyStore(requireContext())
    }

    val deviceBindingService: DeviceBindingService by lazy {
        DeviceBindingService(requireContext())
    }

    // : GoogleAuthService singleton — used by GoogleConnector (registered in ConnectorBootstrap)
    // and IntegrationsViewModel for sign-in flow.
    val googleAuthService: GoogleAuthService by lazy {
        GoogleAuthService(requireContext(), secureStorage)
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

    // : RuntimeProfiler singleton — backend for DeveloperCenter Profiler tab.
    // The object is initialized once; start() is idempotent (multiple calls only
    // add duplicate coroutines which are guarded by isActive).
    val runtimeProfiler: RuntimeProfiler by lazy {
        RuntimeProfiler.apply { start() }
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
                appContext    = requireContext(),
                registry      = reg,
                authManager   = connectorAuthManager,   // P1-7: for GitHubConnector
                llmProviders  = llmProviders,
                secureStorage = secureStorage,          
            )
            // GitHubConnector is now registered inside ConnectorBootstrap.installDefaults.
            // No duplicate registration needed here.
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
        com.airi.assistant.workspace.ArtifactManager(
            context     = requireContext(),
            artifactDao = com.airi.assistant.memory.AiriDatabase
                .getDatabase(requireContext()).artifactDao()
        )
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
            governance     = permissionGovernanceLayer,
            context        = requireContext()
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
            // Wire DurableTaskManager so orchestrator can checkpoint multi-step tasks
            orch.durableTaskManager = durableTaskManager
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

    // : Application-lifetime scope for MemoryManager (summarization, memory extraction).
    // A ViewModel scope would cancel on screen rotation; an IO scope tied to MemoryManager
    // itself worked but forfeited cancellation on app-level shutdown. SupervisorJob ensures
    // a failed child coroutine never cancels the parent or sibling launches.
    val applicationScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    val memoryManager: MemoryManager by lazy {
        MemoryManager(requireContext(), applicationScope)
    }

    // ── Persistent Audit Log (Phase 2 Task 5) ─────────────────────────────────

    val auditRepository: com.airi.assistant.memory.repository.AuditRepository by lazy {
        com.airi.assistant.memory.repository.AuditRepository(
            AiriDatabase.getDatabase(requireContext())
        )
    }

    // ── Storage Repository — unified Room facade ───────────────────────────────

    /**
     * Single shared [StorageRepository] instance across the process.
     * Exposes all 9 Room DAOs through a single facade and owns the
     * GDPR [StorageRepository.deleteAllData] atomic wipe.
     *
     * Preferred over constructing [StorageRepository] ad hoc in consumers;
     * a single instance ensures no DAO accessor races or duplicate DB handles.
     */
    val storageRepository: StorageRepository by lazy {
        StorageRepository(AiriDatabase.getDatabase(requireContext()))
    }

    // ── GDPR Account Deletion Coordinator ────────────────────────────────────

    /**
     * [DataDeletionCoordinator] — orchestrates the full GDPR account-deletion
     * workflow across all 8 deletion steps (WorkManager, Firebase, Room, disk,
     * credentials, preferences, cache, sign-out).
     *
     * UI surfaces (PrivacyDataSettingsScreen) must route through this
     * coordinator rather than calling AuthService.deleteAccount() directly.
     * Direct calls skip 7 of the 8 deletion steps.
     */
    val dataDeletionCoordinator: DataDeletionCoordinator by lazy {
        DataDeletionCoordinator(
            context               = requireContext(),
            authService           = authService,
            storageRepository     = storageRepository,
            artifactManager       = artifactManager,
            preferenceCoordinator = preferenceCoordinator,
            secureStorage         = secureStorage,
            auditRepository       = auditRepository
        )
    }

    // ── Unified Preference Coordinator (Phase 2 Task 6) ───────────────────────

    val preferenceCoordinator: com.airi.assistant.settings.PreferenceCoordinator by lazy {
        com.airi.assistant.settings.PreferenceCoordinator(
            context   = requireContext(),
            execPrefs = com.airi.assistant.execution.prefs.ExecModePreferences(requireContext())
        )
    }

    /**
     * Task 12: Single-source accessor for [ExecModePreferences].
     *
     * All consumers that previously constructed [ExecModePreferences] directly
     * (ChatViewModel, CommandRouter, UnifiedCognitiveLoop, etc.) should obtain
     * the instance from here. This guarantees a single in-memory object backs
     * all reads and writes, eliminating split-brain when preferences change.
     */
    val execModePrefs: com.airi.assistant.execution.prefs.ExecModePreferences
        get() = preferenceCoordinator.rawExecPrefs

    // ── Thermal Profiler + System Health Coordinator (Phase 2 Task 9) ─────────

    val thermalProfiler: com.airi.assistant.runtime.thermal.ThermalProfiler by lazy {
        com.airi.assistant.runtime.thermal.ThermalProfiler(requireContext()).also { it.start() }
    }

    val systemHealthCoordinator: com.airi.assistant.runtime.health.SystemHealthCoordinator by lazy {
        com.airi.assistant.runtime.health.SystemHealthCoordinator(
            context         = requireContext(),
            thermalProfiler = thermalProfiler,
            onThrottleChange = { action ->
                
                // PromptBudgetLedger.forBudget() can apply the correct budget fraction.
                val (factor, emergency) = when (action) {
                    is com.airi.assistant.runtime.health.SystemHealthCoordinator.ThrottleAction.FullPerformance ->
                        1.0f to false
                    is com.airi.assistant.runtime.health.SystemHealthCoordinator.ThrottleAction.ReduceLoad ->
                        action.contextReductionFactor to false
                    is com.airi.assistant.runtime.health.SystemHealthCoordinator.ThrottleAction.EmergencyStop ->
                        0.0f to true
                }
                com.airi.assistant.runtime.health.ThermalSignal.update(factor, emergency)
                // Persist the throttle event to the audit log.
                auditRepository.info(
                    "SYSTEM_HEALTH",
                    "Throttle action: ${action::class.simpleName} contextFactor=$factor emergency=$emergency"
                )
            }
        ).also { it.start() }
    }

    // ── Accessibility Execution Engine ────────────────────────────────────────

    val accessibilityExecutionEngine: AccessibilityExecutionEngine by lazy {
        AccessibilityExecutionEngine()
    }

    // ── Scheduled Job Orchestration ──────────────────────────────────────────

    val scheduledJobOrchestrator: ScheduledJobOrchestrator by lazy {
        val orchestrator = ScheduledJobOrchestrator(requireContext())

        // ── : Scheduled maintenance jobs ─────────────────────────────────
        // Registered here (not in Application.onCreate) so they only start once
        // ServiceLocator is fully initialized and all dependencies are available.

        // Job 1: Sandbox reaper (every 30 min) — eliminates memory leak on high-RAM devices
        // where onTrimMemory is never called.
        orchestrator.schedulePeriodic(
            agentId         = "system",
            payload         = "sandbox_reaper",
            label           = "Prune stale sandbox workspaces",
            intervalMinutes = 30L
        )

        // Job 2: Audit log pruner (every 24h, 30-day retention window)
        orchestrator.schedulePeriodic(
            agentId         = "system",
            payload         = "audit_log_pruner",
            label           = "Prune audit log entries older than 30 days",
            intervalMinutes = 24 * 60L
        )

        // Job 3: Context cache pruner (every 24h)
        orchestrator.schedulePeriodic(
            agentId         = "system",
            payload         = "context_cache_pruner",
            label           = "Prune expired context cache entries",
            intervalMinutes = 24 * 60L
        )

        orchestrator
    }

    // ── Durable Task Manager ───────────────────────────────────────────────────

    val durableTaskManager: DurableTaskManager by lazy {
        DurableTaskManager(requireContext())
    }

    // ── Credit / Consumption Metering ────────────────────────────────────────

    val creditMeteringEngine: CreditMeteringEngine by lazy {
        CreditMeteringEngine(requireContext(), subscriptionManager)
    }

    val tokenAccountant: com.airi.assistant.execution.accounting.TokenAccountant by lazy {
        com.airi.assistant.execution.accounting.TokenAccountant(requireContext())
    }

    // ── Phase 4: Zapier / IFTTT connectors ───────────────────────────────────

    val zapierConnector: com.airi.assistant.connector.app.ZapierConnector by lazy {
        requireNotNull(
            connectorRegistry.get(com.airi.assistant.connector.app.ZapierConnector.CONNECTOR_ID)
                as? com.airi.assistant.connector.app.ZapierConnector
        ) { "ZapierConnector was not registered" }
    }

    val iftttConnector: com.airi.assistant.connector.app.IftttConnector by lazy {
        com.airi.assistant.connector.app.IftttConnector(connectorAuthManager)
    }

    // ── Phase 4: Stripe / Billing ─────────────────────────────────────────────

    val billingHistoryStore: com.airi.assistant.billing.BillingHistoryStore by lazy {
        com.airi.assistant.billing.BillingHistoryStore(requireContext())
    }

    val stripeManager: com.airi.assistant.billing.StripeManager by lazy {
        com.airi.assistant.billing.StripeManager(
            context             = requireContext(),
            subscriptionManager = subscriptionManager,
            billingHistory      = billingHistoryStore,
            authManager         = connectorAuthManager
        )
    }

    // ── Phase 4: Developer Marketplace ────────────────────────────────────────

    val marketplaceRepository: com.airi.assistant.marketplace.MarketplaceRepository by lazy {
        com.airi.assistant.marketplace.MarketplaceRepository(
            context       = requireContext(),
            skillRegistry = com.airi.assistant.ai.skills.SkillExecutor(requireContext()).getRegistry()
        )
    }

    // ── Phase 4: Community Skills ─────────────────────────────────────────────

    val communitySkillHub: com.airi.assistant.community.CommunitySkillHub by lazy {
        com.airi.assistant.community.CommunitySkillHub(requireContext())
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

    // ── Media Library ─────────────────────────────────────────────────────────

    val mediaLibrary: com.airi.assistant.media.MediaLibrary by lazy {
        com.airi.assistant.media.MediaLibrary(requireContext())
    }

    // ── Dynamic Prompt Engine ─────────────────────────────────────────────────
    // Singleton object — exposes the stateless dynamic prompt assembler.
    val dynamicPromptEngine get() = com.airi.assistant.ai.prompt.DynamicPromptEngine

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

    /** Task 11.1: AdaptiveIntelligenceEngine — records outcomes for RL-style adaptation. */
    val adaptiveIntelligenceEngine: com.airi.assistant.agent.learning.AdaptiveIntelligenceEngine by lazy {
        com.airi.assistant.agent.learning.AdaptiveIntelligenceEngine(requireContext())
    }

    /** Task 11.2: PlannerAdaptationEngine — injects learned hints into PlanGenerator. */
    val plannerAdaptationEngine: com.airi.assistant.agent.adaptation.PlannerAdaptationEngine by lazy {
        com.airi.assistant.agent.adaptation.PlannerAdaptationEngine(requireContext()).also { engine ->
            // Wire adaptations into the shared PlanGenerator instance
            engine.applyToGenerator(planGenerator)
        }
    }

    /** Shared PlanGenerator instance — used by orchestrator and adaptation engine. */
    val planGenerator: com.airi.assistant.agent.planning.PlanGenerator by lazy {
        com.airi.assistant.agent.planning.PlanGenerator()
    }

    /** Task 11.3: StrategyEvolutionEngine — learns optimal execution strategies over time. */
    val strategyEvolutionEngine: com.airi.assistant.agent.adaptation.StrategyEvolutionEngine by lazy {
        com.airi.assistant.agent.adaptation.StrategyEvolutionEngine(
            com.airi.assistant.agent.adaptation.PersistentLearningStore(requireContext())
        )
    }

    /** Routes voice transcripts to agents. The active voice session owns audio I/O. */
    val voiceAgentRouter: com.airi.assistant.voice.VoiceAgentRouter by lazy {
        com.airi.assistant.voice.VoiceAgentRouter(
            appContext = requireContext(),
            orchestrator = productionOrchestrator
        )
    }

    /** Set by [initSubAgentSystem]; consumed by ChatViewModel to inject the real confirmation gate. */
    @Volatile var _androidAgent: AndroidAgent? = null
}
