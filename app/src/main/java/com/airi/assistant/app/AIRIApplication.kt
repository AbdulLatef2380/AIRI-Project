package com.airi.assistant.app

import android.app.Application
import android.content.ComponentCallbacks2
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

            // Task 14: Gate install-open analytics behind GDPR consent.
            // AnalyticsService.init() already wires the consentStore internally, but
            // installOpen() fires an event unconditionally on the first launch —
            // which would transmit data before the user has seen the consent screen.
            // Guard it here: only fire if the user has already consented (returning
            // user), or defer until OnboardingScreen grants consent (new user).
            val launchPrefs = getSharedPreferences("airi_launch_funnel", Context.MODE_PRIVATE)
            if (!launchPrefs.getBoolean("install_open_logged", false)) {
                if (ServiceLocator.telemetryConsentStore.current.analyticsEnabled) {
                    AnalyticsService.installOpen()
                    AnalyticsService.funnelStep("install_to_open")
                    launchPrefs.edit().putBoolean("install_open_logged", true).apply()
                }
                // If consent is not yet granted (fresh install), OnboardingScreen is
                // responsible for calling AnalyticsService.installOpen() after consent
                // is confirmed, then setting "install_open_logged" to true.
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

            // ── Phase P6: Permission governance ───────────────────────────────
            // Must run synchronously so the first UCL policy gate check never
            // races against lazy initialization.
            ServiceLocator.permissionGovernanceLayer
            LoggingService.info(TAG, "✓ PermissionGovernanceLayer ready")

            // ── Phase 3: Global agent activity feed ───────────────────────────
            GlobalAgentEventDispatcher.start()
            LoggingService.info(TAG, "✓ GlobalAgentEventDispatcher started")

            // ── Phase 7: Non-critical startup — deferred to background thread ──
            // RAGRetriever, CreditMeteringEngine, ScheduledJobOrchestrator,
            // ChatSharingService, SkillManagerBackend, ReinforcementMemory, and
            // ConnectorHealthMonitor do not need to be ready before the first frame.
            // Moving them to IO reduces cold-start main-thread time by ~60–120 ms
            // on mid-range devices (each lazy init touches SharedPreferences / DB /
            // network, which are blocking I/O operations on the main thread).
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    ServiceLocator.ragRetriever
                    LoggingService.info(TAG, "✓ RAGRetriever initialized (deferred)")

                    ServiceLocator.creditMeteringEngine
                    LoggingService.info(TAG, "✓ CreditMeteringEngine initialized (deferred)")

                    ServiceLocator.scheduledJobOrchestrator
                    LoggingService.info(TAG, "✓ ScheduledJobOrchestrator initialized (deferred)")

                    ServiceLocator.chatSharingService
                    LoggingService.info(TAG, "✓ ChatSharingService initialized (deferred)")

                    ServiceLocator.skillManagerBackend
                    LoggingService.info(TAG, "✓ SkillManagerBackend initialized (deferred)")

                    ReinforcementMemory.init(applicationContext)
                    LoggingService.info(TAG, "✓ ReinforcementMemory loaded (deferred)")

                    // Connector health monitor fires background ping loop — I/O bound
                    ServiceLocator.connectorHealthMonitor
                    LoggingService.info(TAG, "✓ ConnectorHealthMonitor started (deferred)")

                    // Cloud sync is user-preference-gated and network I/O
                    val prefs = ServiceLocator.userProfileRepository.current
                    if (prefs.cloudSyncEnabled) {
                        CloudSyncWorker.enqueue(applicationContext)
                        LoggingService.info(TAG, "✓ CloudSyncWorker enqueued (deferred)")
                    }
                }.onFailure { e ->
                    LoggingService.warn(TAG, "Deferred init error (non-fatal): ${e.message}")
                }
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

    /**
     * Called by Android when the system is running low on memory.
     *
     * At [ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL] or higher we emit
     * a [AppEvent.LowMemoryPressure] so that [ChatViewModel] (which owns
     * [LlamaManager]) can unload the native model and free JNI heap.
     *
     * We do NOT call LlamaManager directly from here because the ViewModel
     * owns the manager instance and is the only safe controller of its
     * lifecycle. The EventBus bridge keeps Application ↔ ViewModel decoupled.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        val severity = when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "CRITICAL"
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW      -> "LOW"
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN        -> "BACKGROUND"
            else                                                       -> null
        } ?: return

        LoggingService.warn(TAG, "onTrimMemory level=$level severity=$severity")
        com.airi.assistant.domain.event.EventBus.emitSync(
            com.airi.assistant.domain.event.AppEvent.LowMemoryPressure(level = level, severity = severity)
        )
        // Evict stale graph workspaces — these hold in-memory file trees and
        // snapshot logs from completed or abandoned executeGraph() runs.
        com.airi.assistant.agent.workspace.WorkspaceRegistry.pruneStale()
        // AP-17: Clear camera JPEG cache when app is backgrounded — prevents heavy camera
        // users accumulating hundreds of MB in cacheDir/chat_attachments/.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { cacheDir.resolve("chat_attachments").deleteRecursively() }
        }
        // Update health monitor so Diagnostics screen reflects the memory event
        runCatching { ServiceLocator.runtimeHealthMonitor }.getOrNull()
            ?.recordMemoryPressure(level)
    }
}
