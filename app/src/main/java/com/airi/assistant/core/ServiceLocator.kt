package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.AgentRouter
import com.airi.assistant.connector.ConnectorBootstrap
import com.airi.assistant.connector.ConnectorRegistry
import com.airi.assistant.connector.api.AnthropicProvider
import com.airi.assistant.connector.api.GeminiProvider
import com.airi.assistant.connector.api.OpenAiProvider
import com.airi.assistant.domain.agent.AgentService
import com.airi.assistant.domain.auth.AuthService
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.observability.AgentObservabilityHub
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.agent.subagent.impl.AndroidAgent
import com.airi.assistant.agent.subagent.impl.CodingAgent
import com.airi.assistant.agent.subagent.impl.MemoryAgent
import com.airi.assistant.agent.subagent.impl.ProductivityAgent
import com.airi.assistant.agent.subagent.impl.ResearchAgent
import com.airi.assistant.accessibility.execution.AccessibilityExecutionEngine
import com.airi.assistant.domain.event.AgentEventStream
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.network.NetworkService
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.domain.prompt.PromptService
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.memory.repository.MemoryManager

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

    // Singleton objects — accessed by reference
    val policyEngine get() = PolicyEngine
    val errorHandler get() = AppErrorHandler

    // ── Monetization ──────────────────────────────────────────────────────────

    val subscriptionManager: SubscriptionManager by lazy {
        SubscriptionManager(requireContext())
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

    val promptService: PromptService by lazy {
        PromptService(requireContext())
    }

    // ── Connectors layer (replaces legacy Integration manager) ───────────────
    //
    // Lazy because installing the default connector set touches
    // SharedPreferences and PackageManager, which we don't want to do at
    // process start. The first read (typically from ConnectorsViewModel)
    // pays the cost.

    val connectorRegistry: ConnectorRegistry by lazy {
        // Build the LLM provider chain from SecureStorage. Each provider
        // takes a `() -> String?` so the key is read every call — rotating
        // a key in Settings takes effect immediately, no registry rebuild.
        val keys = SecureStorage(requireContext())
        // Provider constructors take `keyProvider` as the FIRST positional
        // parameter; trailing-lambda syntax would bind to the LAST parameter
        // (httpClient: OkHttpClient) and fail to compile. Name the param.
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
        }
    }

    val agentRouter: AgentRouter by lazy {
        AgentRouter(connectorRegistry)
    }

    // ── AIRI Ascension: Sub-Agent Layer ───────────────────────────────────────
    //
    // SubAgentRegistry is an object (singleton) — no lazy needed.
    // Initialize it once after ServiceLocator.init() is called.
    // Call ServiceLocator.initSubAgentSystem() from Application.onCreate().

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
    //
    // MemoryManager wraps the Room database (episodic + semantic memory tables).
    // Exposed here so MemoryAgent can receive it via constructor injection
    // without creating a second Room instance.

    val memoryManager: MemoryManager by lazy {
        MemoryManager(requireContext())
    }

    // ── Accessibility Execution Engine ────────────────────────────────────────
    //
    // Wraps AiriAccessibilityService for the OBSERVE→PLAN→EXECUTE→VERIFY loop.
    // No constructor args — the engine reads AiriAccessibilityService.instance at runtime.

    val accessibilityExecutionEngine: AccessibilityExecutionEngine by lazy {
        AccessibilityExecutionEngine()
    }

    /**
     * Initialize the sub-agent system with real tool-injected agents.
     *
     * Called once from Application.onCreate() after [init] has been called.
     *
     * Each agent receives its real tool dependencies here (CalendarTool, AlarmTool,
     * etc.) instead of no-arg stub constructors. The freeze() call is intentionally
     * omitted so that plugin agents registered later (marketplace skills) are not
     * blocked.
     */
    fun initSubAgentSystem() {
        val agents = listOf(
            CodingAgent(),
            ResearchAgent(searchTool),
            AndroidAgent(accessibilityExecutionEngine),
            ProductivityAgent(calendarTool, alarmTool, notesTool),
            MemoryAgent(memoryManager)
        )
        SubAgentRegistry.initialize(agents)
        observabilityHub.refreshRegistrySnapshot()
    }
}
