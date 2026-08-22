package com.airi.assistant.tools.execution

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSourcePolicyTest {

    @Test
    fun permitsOnlyPublicHttpReadSources() {
        assertTrue(
            SearchSourcePolicy.evaluateRead("https://example.com/article")
                is SearchSourcePolicy.Decision.ReadAllowed
        )
        assertTrue(
            SearchSourcePolicy.evaluateRead("http://127.0.0.1/private")
                is SearchSourcePolicy.Decision.Blocked
        )
        assertTrue(
            SearchSourcePolicy.evaluateRead("file:///tmp/secret")
                is SearchSourcePolicy.Decision.Blocked
        )
    }

    @Test
    fun externalOpenRequiresUserTakeover() {
        assertTrue(
            SearchSourcePolicy.evaluateExternalOpen("https://example.com")
                is SearchSourcePolicy.Decision.RequiresUserTakeover
        )
        assertTrue(
            SearchSourcePolicy.evaluateExternalOpen("https://localhost")
                is SearchSourcePolicy.Decision.Blocked
        )
    }
}
