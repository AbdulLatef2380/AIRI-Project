package com.airi.assistant.agent.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationPolicyTest {

    @Test
    fun allowsPublicReadOnlyHttpsNavigation() {
        val decision = BrowserNavigationPolicy.evaluate(
            "https://example.com/article?q=airi",
            BrowserNavigationPolicy.Operation.READ
        )

        assertTrue(decision is BrowserNavigationPolicy.Decision.Allow)
        assertEquals(
            "https://example.com/article?q=airi",
            (decision as BrowserNavigationPolicy.Decision.Allow).normalizedUrl
        )
    }

    @Test
    fun blocksPrivateAndUnsupportedTargets() {
        val privateTarget = BrowserNavigationPolicy.evaluate(
            "http://127.0.0.1:8080/admin",
            BrowserNavigationPolicy.Operation.READ
        )
        val localTarget = BrowserNavigationPolicy.evaluate(
            "https://metadata.internal/latest",
            BrowserNavigationPolicy.Operation.READ
        )
        val unsupported = BrowserNavigationPolicy.evaluate(
            "file:///data/user/0/private.txt",
            BrowserNavigationPolicy.Operation.READ
        )

        assertTrue(privateTarget is BrowserNavigationPolicy.Decision.Blocked)
        assertTrue(localTarget is BrowserNavigationPolicy.Decision.Blocked)
        assertTrue(unsupported is BrowserNavigationPolicy.Decision.Blocked)
    }

    @Test
    fun requiresUserTakeoverForLoginAndPayment() {
        val login = BrowserNavigationPolicy.evaluate(
            "https://accounts.example.com/login",
            BrowserNavigationPolicy.inferOperation("log in to the account")
        )
        val payment = BrowserNavigationPolicy.evaluate(
            "https://shop.example.com/checkout",
            BrowserNavigationPolicy.inferOperation("buy the selected item")
        )

        assertTrue(login is BrowserNavigationPolicy.Decision.RequiresUserTakeover)
        assertTrue(payment is BrowserNavigationPolicy.Decision.RequiresUserTakeover)
    }
}
