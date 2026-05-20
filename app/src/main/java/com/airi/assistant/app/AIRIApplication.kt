package com.airi.assistant.app

import android.app.Application
import android.content.Context
import com.airi.assistant.ui.activity.GlobalAgentEventDispatcher
import com.airi.assistant.ai.remote.RemoteModelRegistry
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.crash.FirebaseCrashReporter
import com.airi.assistant.domain.experiment.ExperimentManager
import com.airi.assistant.domain.growth.OnboardingManager
import com.airi.assistant.domain.growth.ReferralManager
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.integrity.PlayIntegrityVerifier
import com.airi.assistant.memory.AiriDatabase
import com.airi.assistant.sync.CloudSyncWorker
import com.airi.assistant.agent.learning.reinforcement.ReinforcementMemory
import com.airi.assistant.runtime.recovery.RuntimeRecoveryEngine
import com.airi.assistant.system.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AIRIApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LanguageManager.applyLocale(base))
    }

    companion object {
        private const val TAG = "AIRIApplication"
    }

    override fun onCreate() {
        super.onCreate()

        LoggingService.info(TAG, "━━━ AIRI Starting ━━━")

        try {
            ServiceLocator.context = applicationContext
            LoggingService.info(TAG, "✓ ServiceLocator initialized")

            // ── Crash recovery — must be FIRST after ServiceLocator.context ────
            // Installs the UncaughtExceptionHandler that writes a crash timestamp
            // on process death, and logs any prior crash that the app is recovering
            // from. Must run before any subsystem that might throw on init.
            val recoveryEngine = RuntimeRecoveryEngine(applicationContext)
            recoveryEngine.init()
            LoggingService.info(TAG, "✓ RuntimeRecoveryEngine initialized")

            // ── Infrastructure ─────────────────────────────────────────────────
            ServiceLocator.networkService
            LoggingService.info(TAG, "✓ NetworkService initialized")

            ServiceLocator.executionHistoryStore
            LoggingService.info(TAG, "✓ ExecutionHistoryStore initialized")

            ServiceLocator.subscriptionManager
            LoggingService.info(TAG, "✓ SubscriptionManager initialized")

            AiriDatabase.getDatabase(this)
            LoggingService.info(TAG, "✓ Database initialized")

            // ── Identity Layer ─────────────────────────────────────────────────
            ServiceLocator.sessionManager
            LoggingService.info(TAG, "✓ SessionManager initialized")

            // ── User Profile Runtime ───────────────────────────────────────────
            ServiceLocator.userProfileRepository
            LoggingService.info(TAG, "✓ UserProfileRepository initialized")

            // ── Privacy / Telemetry ────────────────────────────────────────────
            ServiceLocator.telemetryConsentStore
            ServiceLocator.privacyTelemetryReporter
            LoggingService.info(TAG, "✓ PrivacyTelemetryReporter initialized")

            // ── Growth & Analytics ─────────────────────────────────────────────
            // Pass consentStore so AnalyticsService gates Firebase on opt-in.
            AnalyticsService.init(this, ServiceLocator.telemetryConsentStore)
            LoggingService.info(TAG, "✓ AnalyticsService initialized")

            val launchPrefs = getSharedPreferences("airi_launch_funnel", Context.MODE_PRIVATE)
            if (!launchPrefs.getBoolean("install_open_logged", false)) {
                AnalyticsService.installOpen()
                AnalyticsService.funnelStep("install_to_open")
                launchPrefs.edit().putBoolean("install_open_logged", true).apply()
            }

            OnboardingManager.init(this)
            ReferralManager.init(this)
            LoggingService.info(TAG, "✓ Growth managers initialized")

            PaywallTriggerEngine.init(this)
            LoggingService.info(TAG, "✓ PaywallTriggerEngine initialized")

            RetentionManager.init(this)
            RetentionManager.incrementSession()
            RetentionManager.scheduleReEngagementReminder(this)
            LoggingService.info(TAG, "✓ RetentionManager initialized")

            ExperimentManager.init(this)
            LoggingService.info(TAG, "✓ ExperimentManager initialized")

            RemoteModelRegistry.init(this)
            LoggingService.info(TAG, "✓ RemoteModelRegistry initialized")

            // ── Crash / Runtime Reporting ──────────────────────────────────────
            ServiceLocator.crashReportStore
            ServiceLocator.crashReporter
            LoggingService.info(TAG, "✓ CrashReporter initialized")

            // ── Firebase Crashlytics ───────────────────────────────────────────
            // Enrich every crash report with session metadata so triage is fast.
            // Collection remains OFF until the user grants telemetry consent
            // (OnboardingScreen calls FirebaseCrashReporter.enableCollection()).
            FirebaseCrashReporter.setKey("app_version", "1.0")
            FirebaseCrashReporter.setKey("exec_mode",
                ServiceLocator.context?.let {
                    com.airi.assistant.execution.prefs.ExecModePreferences(it)
                        .effectiveMode.name
                } ?: "UNKNOWN"
            )
            // Enable collection if user already consented in a prior session.
            val consentStore = ServiceLocator.telemetryConsentStore
            if (consentStore.current.crashReportingEnabled) {
                FirebaseCrashReporter.enableCollection()
            }
            LoggingService.info(TAG, "✓ FirebaseCrashReporter configured")

            ServiceLocator.runtimeHealthMonitor.start()
            LoggingService.info(TAG, "✓ RuntimeHealthMonitor started")

            // ── AIRI Ascension: Sub-Agent + Orchestration ──────────────────────
            ServiceLocator.initSubAgentSystem()
            LoggingService.info(TAG, "✓ SubAgentSystem + PermissionRegistry initialized")

            // ── Agent Operating Layer (architecture expansion) ─────────────
            ServiceLocator.cotEngine
            ServiceLocator.reActPlanner
            LoggingService.info(TAG, "✓ CoT/ReAct planner initialized")

            ServiceLocator.ragRetriever
            LoggingService.info(TAG, "✓ RAG retriever initialized")

            ServiceLocator.creditMeteringEngine
            LoggingService.info(TAG, "✓ CreditMeteringEngine initialized")

            ServiceLocator.modelGovernanceEngine
            LoggingService.info(TAG, "✓ ModelGovernanceEngine initialized")

            ServiceLocator.scheduledJobOrchestrator
            LoggingService.info(TAG, "✓ ScheduledJobOrchestrator initialized")

            ServiceLocator.chatSharingService
            LoggingService.info(TAG, "✓ ChatSharingService initialized")

            ServiceLocator.skillManagerBackend
            LoggingService.info(TAG, "✓ SkillManagerBackend initialized")

            // ── Reinforcement Learning (persistent across sessions) ─────────────
            ReinforcementMemory.init(applicationContext)
            LoggingService.info(TAG, "✓ ReinforcementMemory loaded")

            // ── Execution Watchdog ─────────────────────────────────────────────
            ServiceLocator.executionWatchdog.start()
            LoggingService.info(TAG, "✓ ExecutionWatchdog started")

            // ── Phase 3: Global agent activity feed ───────────────────────────
            GlobalAgentEventDispatcher.start()
            LoggingService.info(TAG, "✓ GlobalAgentEventDispatcher started")

            // ── Phase 7: Connector ecosystem ──────────────────────────────────
            ServiceLocator.connectorHealthMonitor   // triggers lazy init + background ping loop
            LoggingService.info(TAG, "✓ ConnectorHealthMonitor started")

            // ── Phase P2: Multi-agent capability graph ─────────────────────────
            com.airi.assistant.agent.multiagent.AgentCapabilityGraph.installDefaults()
            LoggingService.info(TAG, "✓ AgentCapabilityGraph installed (${com.airi.assistant.agent.multiagent.AgentCapabilityGraph.allActive().size} agents)")

            // ── Phase P6: Permission governance ───────────────────────────────
            ServiceLocator.permissionGovernanceLayer   // triggers lazy init
            LoggingService.info(TAG, "✓ PermissionGovernanceLayer ready")

            // ── Cloud Sync ─────────────────────────────────────────────────────
            val prefs = ServiceLocator.userProfileRepository.current
            if (prefs.cloudSyncEnabled) {
                CloudSyncWorker.enqueue(this)
                LoggingService.info(TAG, "✓ CloudSyncWorker enqueued")
            }

            // ── Session analytics ──────────────────────────────────────────────
            AnalyticsService.appOpen()
            AnalyticsService.sessionStart()

            // ── Play Integrity warm-up (async — non-blocking startup) ──────────
            // Fires a background integrity token request so that the first
            // high-trust action (cloud call, subscription purchase) already has
            // a cached verdict available without blocking the user.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                PlayIntegrityVerifier.warmUp(applicationContext)
            }

            LoggingService.info(TAG, "━━━ AIRI Ready ━━━")

        } catch (e: Exception) {
            LoggingService.error(TAG, "Initialization error: ${e.message}", e)
            throw e
        }
    }
}
