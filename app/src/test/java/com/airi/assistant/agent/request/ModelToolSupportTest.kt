package com.airi.assistant.agent.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelToolSupportTest {
    @Test fun nativeCloudProviders() {
        listOf("openai", "anthropic", "gemini", "OpenAI").forEach {
            assertEquals(it, ModelToolSupport.NATIVE, ModelToolSupport.resolve(it))
        }
    }

    @Test fun openRouterFollowsTheCatalog() {
        assertEquals(ModelToolSupport.NATIVE, ModelToolSupport.resolve("openrouter", declaresTools = true))
        assertEquals(ModelToolSupport.NONE, ModelToolSupport.resolve("openrouter", declaresTools = false))
        assertEquals(ModelToolSupport.TEXT_PROTOCOL, ModelToolSupport.resolve("openrouter", declaresTools = null))
    }

    @Test fun localModelNeedsVerification() {
        assertEquals(ModelToolSupport.NONE, ModelToolSupport.resolve("local"))
        assertEquals(ModelToolSupport.TEXT_PROTOCOL, ModelToolSupport.resolve("local", localToolsVerified = true))
        assertFalse(ModelToolSupport.NONE.canCallTools)
    }
}
