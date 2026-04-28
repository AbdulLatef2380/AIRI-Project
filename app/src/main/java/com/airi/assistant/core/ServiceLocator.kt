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
import com.airi.assistant.domain.event.AgentEventStream
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.network.NetworkService
import com.airi.assistant.domain.permission.PermissionService
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.domain.prompt.PromptService
import com.airi.assistant.domain.skill.SkillService

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
}
