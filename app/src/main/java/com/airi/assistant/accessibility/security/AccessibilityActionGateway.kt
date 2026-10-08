package com.airi.assistant.accessibility.security

/**
 * Shared, side-effect-free admission policy for every accessibility read/action.
 * Callers must invoke this before obtaining a node tree or dispatching an action.
 */
object AccessibilityActionGateway {
    enum class Action { READ_SCREEN, OPEN_APP, TAP, TYPE_TEXT, SCROLL, NAVIGATE_BACK, NAVIGATE_HOME, SEARCH }
    sealed interface Decision {
        data object Allow : Decision
        data class Deny(val reason: String) : Decision
        data class NeedsConfirmation(val reason: String) : Decision
    }

    fun authorize(packageName: String, action: Action, payload: String = "", confirmed: Boolean = false): Decision {
        if (packageName.isBlank() || packageName == "unknown") return Decision.Deny("active package is unavailable")
        when (AccessibilityPolicyGuard.checkPackage(packageName)) {
            is AccessibilityPolicyGuard.PolicyDecision.Denied ->
                return Decision.Deny("package is protected")
            AccessibilityPolicyGuard.PolicyDecision.Allowed -> Unit
        }
        if (action == Action.READ_SCREEN && !AccessibilityScopePolicy.isPackageReadAllowed(packageName)) {
            return Decision.Deny("screen reads are disabled for this package")
        }
        if (action != Action.READ_SCREEN && AccessibilityPolicyGuard.requiresConfirmation(payload) && !confirmed) {
            return Decision.NeedsConfirmation("explicit confirmation required for ${action.name.lowercase()}")
        }
        return Decision.Allow
    }

}
