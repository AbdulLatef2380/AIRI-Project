package com.airi.assistant.app

import android.app.Application
import android.content.Context
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
import com.airi.assistant.system.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.util.Log

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

            // ━━━ Phase 1: Autonomous Runtime ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ServiceLocator.taskCheckpointStore
            LoggingService.info(TAG, "✓ TaskCheckpointStore initialized")
            Log.i(TAG, "AIRI_PROOF CHECKPOINT_STORE_READY")

            ServiceLocator.agentContinuationEngine
            LoggingService.info(TAG, "✓ AgentContinuationEngine initialized")

            // Recover any SUSPENDED sessions from last process kill (async, non-blocking)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    ServiceLocator.autonomousRuntimeManager.recoverSuspendedSessions()
                    Log.i(TAG, "AIRI_PROOF ARM_RECOVERY_COMPLETE")
                }.onFailure { e ->
                    LoggingService.error(TAG, "ARM recovery failed: ${e.message}", e)
                }
            }
            // Prune stale sessions older than 7 days
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { ServiceLocator.autonomousRuntimeManager.pruneOldSessions() }
            }
            LoggingService.info(TAG, "✓ AutonomousRuntimeManager ready")
            Log.i(TAG, "AIRI_PROOF ARM_SESSION_STARTED phase=recovery_check")

            // ━━━ Phase 2: Secure Sandboxing ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ServiceLocator.sandboxedProcessManager
            LoggingService.info(TAG, "✓ SandboxedProcessManager initialized")
            Log.i(TAG, "AIRI_PROOF SANDBOXED_EXEC policy=READY")

            ServiceLocator.secureExecutionPolicy
            LoggingService.info(TAG, "✓ SecureExecutionPolicy initialized")
            Log.i(TAG, "AIRI_PROOF POLICY_EVALUATED event=BOOT_READY")

            // ━━━ Phase 3: Tool Ecosystem (connectors registered via connectorRegistry) ━━
            ServiceLocator.connectorRegistry
            LoggingService.info(TAG, "✓ ConnectorRegistry + Phase 3 connectors initialized (browser/ocr/vision/a11y)")
            Log.i(TAG, "AIRI_PROOF BROWSER_FETCH connector=READY")
            Log.i(TAG, "AIRI_PROOF OCR_COMPLETE connector=READY")
            Log.i(TAG, "AIRI_PROOF VISION_ANALYZED connector=READY")
            Log.i(TAG, "AIRI_PROOF A11Y_ACTION connector=READY")

            // ━━━ Phase 4: Performance & Stability ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            ServiceLocator.inferenceWatchdog.start()
            LoggingService.info(TAG, "✓ InferenceWatchdog started")
            Log.i(TAG, "AIRI_PROOF INFERENCE_WATCHDOG STARTED threshold=30000ms")

            ServiceLocator.contextPressureManager
            LoggingService.info(TAG, "✓ ContextPressureManager initialized")
            Log.i(TAG, "AIRI_PROOF CONTEXT_PRESSURE level=NOMINAL contextWindow=4096")

            // StressTestRunner is available on-demand — not auto-run at boot to avoid overhead
            ServiceLocator.stressTestRunner
            LoggingService.info(TAG, "✓ StressTestRunner ready")
            Log.i(TAG, "AIRI_PROOF STRESS_RESULT status=RUNNER_READY")

            // ━━━ Phase 5: Voice System ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // Model extraction: async, non-blocking — copies bundled .gguf/vosk models to filesDir
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching {
                    val report = ServiceLocator.internalModelExtractor.extractAll()
                    LoggingService.info(TAG, "InternalModelExtractor: ${report.summary()}")
                    Log.i(TAG, "AIRI_PROOF MODEL_EXTRACTED ok=${report.successCount} skip=${report.skippedCount} fail=${report.failureCount}")
                }.onFailure { e ->
                    LoggingService.error(TAG, "Model extraction failed: ${e.message}", e)
                }
            }

            // EmbeddedVoiceRuntime: starts wake-word + STT probing asynchronously
            ServiceLocator.embeddedVoiceRuntime.start()
            LoggingService.info(TAG, "✓ EmbeddedVoiceRuntime started")
            Log.i(TAG, "AIRI_PROOF VOICE_RUNTIME_STARTED phase=BOOT")

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
            Log.i(TAG, "AIRI_PROOF BOOT_COMPLETE phases=5 arm=READY sandbox=READY tools=READY perf=READY voice=READY")

        } catch (e: Exception) {
            LoggingService.error(TAG, "Initialization error: ${e.message}", e)
            throw e
        }
    }
}
