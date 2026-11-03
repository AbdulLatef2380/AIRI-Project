package com.airi.assistant.agent.browser

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserNavigationPolicyHardeningTest {
    @Test
    fun rejectsAlternativeIpv4AndIpv6PrivateTargets() {
        listOf(
            "http://2130706433/", "http://0177.0.0.1/", "http://0x7f.0.0.1/",
            "http://[::1]/", "http://[fd00::1]/", "http://[::ffff:127.0.0.1]/", "http://0.1.2.3/",
        ).forEach { assertNull("expected rejection: $it", BrowserNavigationPolicy.normalizePublicHttpUrl(it)) }
    }

    @Test
    fun stillAcceptsPublicTargets() {
        assertNotNull(BrowserNavigationPolicy.normalizePublicHttpUrl("https://example.com/a?b=c"))
        assertNotNull(BrowserNavigationPolicy.normalizePublicHttpUrl("https://8.8.8.8/"))
    }
}
