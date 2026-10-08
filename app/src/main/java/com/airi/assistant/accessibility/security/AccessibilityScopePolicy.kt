package com.airi.assistant.accessibility.security

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityScopePolicy — per-package gating for accessibility read operations.
 *
 * ## Why this exists
 * [com.airi.assistant.accessibility.service.AiriAccessibilityService] subscribes
 * to `typeAllMask` accessibility events, meaning it receives events from every
 * app on the device. Without gating, agent features like "what's on my screen?"
 * would expose content from banking apps, password managers, OTP apps, and
 * other high-sensitivity surfaces.
 *
 * ## What it does
 * [readsAllowedFor] delegates to [AccessibilityActionGateway], the canonical
 * policy for package reads and UI actions. The
 * accessibility service uses this before publishing [ScreenState] node content
 * to upstream consumers. The package name is still reported (so the AIRI runtime
 * can know "user is in BankingApp"), but the node tree / text content is redacted.
 *
 * ## Thread safety
 * [readsAllowedFor] is safe to call from any thread. [state] is a StateFlow,
 * safe for any observer.
 */
class AccessibilityScopePolicy private constructor(context: Context) {

    enum class PolicyMode { PERMISSIVE, CONSERVATIVE }

    data class PolicyState(val mode: PolicyMode)

    private val _state = MutableStateFlow(PolicyState(mode = PolicyMode.CONSERVATIVE))
    val state: StateFlow<PolicyState> = _state.asStateFlow()

    /**
     * Returns true if AIRI is allowed to read and surface node content from
     * [packageName] through accessibility APIs.
     *
     * Returns false for:
     *  - Banking and financial apps
     *  - Password managers
     *  - OTP / 2FA apps
     *  - System UI / Launcher components that could expose notification tickers
     *  - Keyboard / IME packages (would expose keystrokes across every app)
     */
    fun readsAllowedFor(packageName: String): Boolean =
        AccessibilityActionGateway.authorize(packageName, "read_screen") is AccessibilityActionGateway.Decision.Allowed

    companion object {
        private const val TAG = "AccessibilityScopePolicy"

        // ── Singleton ─────────────────────────────────────────────────────────────
        @Volatile private var instance: AccessibilityScopePolicy? = null

        fun get(context: Context): AccessibilityScopePolicy =
            instance ?: synchronized(this) {
                instance ?: AccessibilityScopePolicy(context.applicationContext)
                    .also {
                        instance = it
                        Log.i(TAG, "AccessibilityScopePolicy initialised (mode=CONSERVATIVE, shared gateway)")
                    }
            }
    }
}
