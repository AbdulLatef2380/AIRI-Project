package com.airi.assistant.accessibility.security

/**
 * Shared fail-closed gate for accessibility reads and UI actions.
 *
 * This gate intentionally does not manufacture user approval. Actions that need
 * confirmation are rejected until a caller can provide a request-bound approval
 * token; a model-generated confirmation string is not authorization.
 */
object AccessibilityActionGateway {
    sealed interface Decision {
        data object Allowed : Decision
        data class Denied(val reason: String) : Decision
        data class NeedsConfirmation(val reason: String) : Decision
    }

    fun authorize(packageName: String, action: String, target: String = ""): Decision {
        if (packageName.isBlank() || packageName == "unknown") {
            return Decision.Denied("Active package is unknown; accessibility operation refused.")
        }
        return try {
            when (val decision = AccessibilityPolicyGuard.checkPackage(packageName)) {
                AccessibilityPolicyGuard.PolicyDecision.Allowed -> Unit
                is AccessibilityPolicyGuard.PolicyDecision.Denied ->
                    return Decision.Denied(decision.reason)
            }
            val normalizedPackage = packageName.lowercase()
            if (normalizedPackage in READ_DENIED_PACKAGES || READ_DENIED_PREFIXES.any(normalizedPackage::startsWith)) {
                return Decision.Denied("Accessibility is disabled for this sensitive package.")
            }
            val lower = "$action $target".lowercase()
            if (CONFIRMATION_ACTIONS.any { it in action.lowercase() } ||
                AccessibilityPolicyGuard.requiresConfirmation(lower) ||
                ARABIC_CONFIRMATION_TERMS.any(lower::contains)
            ) {
                Decision.NeedsConfirmation("Explicit, request-bound user approval is required for this action.")
            } else {
                Decision.Allowed
            }
        } catch (_: Exception) {
            Decision.Denied("Accessibility policy evaluation failed; operation refused.")
        }
    }

    private val READ_DENIED_PACKAGES = setOf(
        "com.android.systemui", "com.android.launcher3", "com.google.android.apps.nexuslauncher",
        "com.sec.android.app.launcher", "com.miui.home", "com.huawei.android.launcher",
        "com.google.android.inputmethod.latin", "com.swiftkey.swiftkeyapp", "com.touchtype.swiftkey",
        "com.grammarly.android.keyboard", "com.nuance.swype.swype", "com.microsoft.swiftkey",
        "com.google.android.apps.authenticator2", "com.authy.authy", "com.microsoft.authenticator",
        "org.fedorahosted.freeotp", "com.twilio.authy2", "me.dm7.barcodescanner.zxing",
        "com.lastpass.lpandroid", "com.lastpass.authenticator", "com.onepassword.android",
        "com.agilebits.onepassword", "com.dashlane", "com.bitwarden.mobile",
        "org.keepass2android.app", "keepass2android.keepass2android", "com.x8bit.bitwarden",
        "com.keepassdroid", "com.google.android.gms", "com.google.android.gsf",
        "com.google.android.gsf.login", "com.android.settings", "com.android.packageinstaller",
        "com.android.permissioncontroller",
    )
    private val READ_DENIED_PREFIXES = setOf(
        "com.android.bankapp", "uk.co.hsbc", "com.barclays", "com.chase.sig", "com.usaa",
        "com.wellsfargo", "com.schwab", "com.fidelity", "com.paypal", "com.venmo",
        "com.cashapp", "com.coinbase", "com.binance", "com.kraken", "com.lastpass",
        "com.onepassword", "com.agilebits", "com.dashlane", "com.keepassdroid",
        "com.bitwarden", "org.keepassj", "net.tjado.passwds", "com.duo.mobile",
        "com.yubico.yubioath", "com.chase", "com.bankofamerica", "com.wellsfargo",
        "com.citibank", "com.usbank", "com.capitalone", "com.tdbank", "com.pnc",
        "com.regions", "com.truist", "com.ally", "com.hsbc", "com.lloydsbank",
        "com.natwest", "com.santander", "com.revolut", "com.monzo", "com.starlingbank",
        "com.n26", "com.bunq", "com.alrajhibank", "com.ncb", "com.riyadbank",
    )
    private val CONFIRMATION_ACTIONS = setOf("send", "delete", "publish", "post", "submit", "upload", "transfer", "purchase", "payment")
    private val ARABIC_CONFIRMATION_TERMS = setOf("أرسل", "احذف", "انشر", "أرسل", "ادفع", "اشتر", "حوّل", "امسح")
}
