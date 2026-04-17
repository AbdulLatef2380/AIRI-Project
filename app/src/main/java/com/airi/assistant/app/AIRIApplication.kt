package com.airi.assistant.app

import android.app.Application
import android.util.Log
import com.airi.assistant.core.AiriLogger
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.memory.AiriDatabase
import com.google.firebase.crashlytics.FirebaseCrashlytics

class AIRIApplication : Application() {

    companion object {
        private const val TAG = "AIRIApplication"
    }

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "━━━ AIRI Starting ━━━")

        try {
            initCrashlytics()
            installGlobalExceptionHandler()

            ServiceLocator.context = applicationContext
            Log.d(TAG, "✓ ServiceLocator initialized")

            AiriDatabase.getDatabase(this)
            Log.d(TAG, "✓ Database initialized")

            Log.d(TAG, "━━━ AIRI Ready ━━━")

        } catch (e: Exception) {
            Log.e(TAG, "Initialization error: ${e.message}", e)
            throw e
        }
    }

    private fun initCrashlytics() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            crashlytics.setCrashlyticsCollectionEnabled(true)
            val versionName = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrDefault("unknown")
            crashlytics.setCustomKey("app_version", versionName ?: "unknown")
            crashlytics.log("AIRI App Started — v$versionName")
            Log.d(TAG, "✓ Crashlytics initialized")
        } catch (e: Exception) {
            Log.w(TAG, "Crashlytics not available: ${e.message}")
        }
    }

    private fun installGlobalExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                AiriLogger.e("UNCAUGHT EXCEPTION on thread=${thread.name}: ${throwable.message}", throwable)
                FirebaseCrashlytics.getInstance().recordException(throwable)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
