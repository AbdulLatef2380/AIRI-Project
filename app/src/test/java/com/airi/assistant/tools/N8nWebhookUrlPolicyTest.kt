package com.airi.assistant.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class N8nWebhookUrlPolicyTest {
    @Test
    fun acceptsHttpsAndDerivesHealthUrlWithoutQuerySecrets() {
        val result = N8nWebhookUrlPolicy.validate("https://automation.example/n8n/webhook/secret-token?auth=hidden")
        assertTrue(result is N8nWebhookUrlPolicy.Validation.Accepted)
        val accepted = result as N8nWebhookUrlPolicy.Validation.Accepted
        assertEquals("https://automation.example/n8n/healthz", accepted.healthCheck.toString())
        assertEquals("https://automation.example/n8n/webhook/secret-token?auth=hidden", accepted.webhook.toString())
    }

    @Test
    fun permitsHttpOnlyForLoopbackDevelopmentEndpoints() {
        assertTrue(N8nWebhookUrlPolicy.validate("http://localhost:5678/webhook/airi") is N8nWebhookUrlPolicy.Validation.Accepted)
        assertTrue(N8nWebhookUrlPolicy.validate("http://192.168.1.20:5678/webhook/airi") is N8nWebhookUrlPolicy.Validation.Rejected)
    }

    @Test
    fun rejectsUserInfoFragmentsAndMissingPath() {
        assertEquals(N8nWebhookUrlPolicy.Reason.USER_INFO,
            (N8nWebhookUrlPolicy.validate("https://user:pass@example.com/webhook/x") as N8nWebhookUrlPolicy.Validation.Rejected).reason)
        assertEquals(N8nWebhookUrlPolicy.Reason.FRAGMENT,
            (N8nWebhookUrlPolicy.validate("https://example.com/webhook/x#secret") as N8nWebhookUrlPolicy.Validation.Rejected).reason)
        assertEquals(N8nWebhookUrlPolicy.Reason.MISSING_PATH,
            (N8nWebhookUrlPolicy.validate("https://example.com") as N8nWebhookUrlPolicy.Validation.Rejected).reason)
    }
}
