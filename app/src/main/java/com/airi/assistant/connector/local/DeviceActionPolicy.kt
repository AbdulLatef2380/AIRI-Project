package com.airi.assistant.connector.local

import com.airi.assistant.agent.browser.BrowserNavigationPolicy

/**
 * Boundary for local device discovery versus actions that transfer control
 * outside AIRI. Listing/searching apps is read-only; launching an app or a
 * browser must be initiated by a user-controlled surface.
 */
object DeviceActionPolicy {
    sealed class Decision {
        data object Allowed : Decision()
        data class RequiresUserTakeover(val reason: String) : Decision()
        data class Blocked(val reason: String) : Decision()
    }

    fun evaluate(action: String, url: String? = null): Decision = when (action) {
        "list_apps", "find_app" -> Decision.Allowed
        "open_app" -> Decision.RequiresUserTakeover(
            "Opening another app transfers control outside AIRI and must be initiated by the user"
        )
        "open_settings" -> Decision.RequiresUserTakeover(
            "Opening device settings transfers control outside AIRI and must be initiated by the user"
        )
        "open_url" -> when (val browser = BrowserNavigationPolicy.evaluate(url.orEmpty(), BrowserNavigationPolicy.Operation.OPEN_EXTERNAL)) {
            is BrowserNavigationPolicy.Decision.RequiresUserTakeover -> Decision.RequiresUserTakeover(browser.reason)
            is BrowserNavigationPolicy.Decision.Blocked -> Decision.Blocked(browser.reason)
            else -> Decision.Blocked("Opening the URL is not permitted by the navigation policy")
        }
        else -> Decision.Allowed
    }
}
