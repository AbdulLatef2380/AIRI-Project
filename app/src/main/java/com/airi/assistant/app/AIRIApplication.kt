package com.airi.assistant.app

import android.app.Application
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.experiment.ExperimentManager
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import com.airi.assistant.domain.retention.RetentionManager
import com.airi.assistant.memory.AiriDatabase

class AIRIApplication : Application() {

    companion object {
        private const val TAG = "AIRIApplication"
    }

    override fun onCreate() {
        super.onCreate()

        LoggingService.info(TAG, "━━━ AIRI Starting ━━━")

        try {
            ServiceLocator.context = applicationContext
            LoggingService.info(TAG, "✓ ServiceLocator initialized")

            // Eagerly initialize connectivity monitoring
            ServiceLocator.networkService
            LoggingService.info(TAG, "✓ NetworkService initialized")

            // Eagerly initialize event history — subscribes to EventBus immediately
            ServiceLocator.executionHistoryStore
            LoggingService.info(TAG, "✓ ExecutionHistoryStore initialized")

            // Eagerly initialize subscription manager so daily reset is ready
            ServiceLocator.subscriptionManager
            LoggingService.info(TAG, "✓ SubscriptionManager initialized")

            AiriDatabase.getDatabase(this)
            LoggingService.info(TAG, "✓ Database initialized")

            // ── Growth & Analytics Systems ─────────────────────────────────────

            AnalyticsService.init(this)
            LoggingService.info(TAG, "✓ AnalyticsService initialized")

            PaywallTriggerEngine.init(this)
            LoggingService.info(TAG, "✓ PaywallTriggerEngine initialized")

            RetentionManager.init(this)
            RetentionManager.incrementSession()
            LoggingService.info(TAG, "✓ RetentionManager initialized")

            ExperimentManager.init(this)
            LoggingService.info(TAG, "✓ ExperimentManager initialized")

            // Fire app_open analytics event
            AnalyticsService.appOpen()
            AnalyticsService.sessionStart()

            LoggingService.info(TAG, "━━━ AIRI Ready ━━━")

        } catch (e: Exception) {
            LoggingService.error(TAG, "Initialization error: ${e.message}", e)
            throw e
        }
    }
}
