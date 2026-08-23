package com.airi.assistant.agent.subagent.impl

import com.airi.assistant.agent.browser.BrowserNavigationPolicy
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrowserOperatorPolicyTest {

    @Test
    fun publicHttpsRequiresUserTakeoverInsteadOfAutonomousLaunch() {
        val decision = LocalBrowserOperator.handoffDecision("https://example.com/search?q=airi")

        assertTrue(decision is BrowserNavigationPolicy.Decision.RequiresUserTakeover)
    }

    @Test
    fun privateHttpTargetIsBlockedBeforeExternalHandoff() {
        val decision = LocalBrowserOperator.handoffDecision("http://127.0.0.1:8080/admin")

        assertTrue(decision is BrowserNavigationPolicy.Decision.Blocked)
    }

    @Test
    fun mapsDeepLinkStillRequiresUserTakeover() {
        val decision = LocalBrowserOperator.handoffDecision("geo:0,0?q=Berlin")

        assertTrue(decision is BrowserNavigationPolicy.Decision.RequiresUserTakeover)
    }
}
