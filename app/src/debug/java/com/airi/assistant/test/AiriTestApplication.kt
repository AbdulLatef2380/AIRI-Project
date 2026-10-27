package com.airi.assistant.test

import android.app.Application
import com.airi.assistant.core.ServiceLocator

/**
 * Minimal application used only by debug instrumentation tests.
 *
 * Production startup initializes analytics, WorkManager, agent infrastructure,
 * and recovery services. That graph is not required by the Android tests and
 * can exceed the emulator's startup/ANR budget before the runner discovers
 * tests. Keep the target process deterministic while preserving the real
 * application for release builds.
 */
class AiriTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(applicationContext)
    }
}
