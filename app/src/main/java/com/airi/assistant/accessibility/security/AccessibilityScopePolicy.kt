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
 * [readsAllowedFor] returns false for any package in [DENIED_PACKAGES]. The
 * accessibility service uses this before publishing [ScreenState] node content
 * to upstream consumers. The package name is still reported (so the AIRI runtime
 * can know "user is in BankingApp"), but the node tree / text content is redacted.
 *
 * ## What it does NOT (yet) do
 * Autonomous action gating (taps, swipes, typing via AccessibilityCommandBridge)
 * is NOT yet wired to this policy. That is a subsequent integration pass.
 *
 * ## Thread safety
 * [readsAllowedFor] is safe to call from any thread (pure function over
 * an immutable set). [state] is a StateFlow, safe for any observer.
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
    fun readsAllowedFor(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        // Exact package name matches
        if (packageName in DENIED_PACKAGES) return false
        // Prefix-based denylist for family groups (e.g. all "com.google.android.apps.authenticator2.*")
        if (DENIED_PREFIXES.any { packageName.startsWith(it) }) return false
        return true
    }

    companion object {
        private const val TAG = "AccessibilityScopePolicy"

        /**
         * Packages that are always denied for accessibility reads.
         * This list is intentionally conservative — false positives (denying
         * reads from a non-sensitive app that happens to share a prefix) are
         * far less dangerous than false negatives (leaking banking credentials).
         */
        private val DENIED_PACKAGES: Set<String> = setOf(
            // ── System UI ────────────────────────────────────────────────────────
            "com.android.systemui",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",   // Samsung
            "com.miui.home",                   // Xiaomi
            "com.huawei.android.launcher",

            // ── Keyboard / IME ───────────────────────────────────────────────────
            "com.google.android.inputmethod.latin",
            "com.swiftkey.swiftkeyapp",
            "com.touchtype.swiftkey",
            "com.grammarly.android.keyboard",
            "com.nuance.swype.swype",
            "com.microsoft.swiftkey",

            // ── Password managers ────────────────────────────────────────────────
            "com.lastpass.lpandroid",
            "com.lastpass.authenticator",
            "com.onepassword.android",
            "com.agilebits.onepassword",
            "com.dashlane",
            "com.bitwarden.mobile",
            "org.keepass2android.app",
            "keepass2android.keepass2android",
            "com.x8bit.bitwarden",
            "com.keepassdroid",

            // ── 2FA / OTP apps ───────────────────────────────────────────────────
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.microsoft.authenticator",
            "com.duo.mobile",
            "org.fedorahosted.freeotp",
            "com.twilio.authy2",
            "me.dm7.barcodescanner.zxing",

            // ── Google account / Android auth ────────────────────────────────────
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.gsf.login",

            // ── Android OS security surfaces ──────────────────────────────────────
            "com.android.settings",
            "com.android.packageinstaller",
            "com.android.permissioncontroller"
        )

        private val DENIED_PREFIXES: Set<String> = setOf(
            "com.android.bankapp",
            "uk.co.hsbc",
            "com.barclays",
            "com.chase.sig",
            "com.usaa",
            "com.wellsfargo",
            "com.schwab",
            "com.fidelity",
            "com.paypal",
            "com.venmo",
            "com.cashapp",
            "com.coinbase",
            "com.binance",
            "com.kraken"
        )

        // ── Singleton ─────────────────────────────────────────────────────────────
        @Volatile private var instance: AccessibilityScopePolicy? = null

        fun get(context: Context): AccessibilityScopePolicy =
            instance ?: synchronized(this) {
                instance ?: AccessibilityScopePolicy(context.applicationContext)
                    .also {
                        instance = it
                        Log.i(TAG, "AccessibilityScopePolicy initialised (mode=CONSERVATIVE, " +
                            "denied=${DENIED_PACKAGES.size} packages, " +
                            "prefixes=${DENIED_PREFIXES.size})")
                    }
            }
    }
}
