package com.airi.assistant.execution.privacy

import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.PrivacyLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyGuardTest {

    @Test
    fun balancedPrivacySanitizesEveryCloudSerializedTextField() {
        val rawImei = "490154203237518"
        val rawSerial = "R58M123ABC9"
        val result = PrivacyGuard.evaluate(
            request = ExecutionRequest(
                prompt = "Inspect /data/user/0/com.airi/cache and IMEI: $rawImei",
                systemPrompt = "Use serial number: $rawSerial",
                conversationHistory = listOf(
                    ExecutionRequest.ConversationTurn(
                        role = "user",
                        content = "Token Bearer abcdefghijklmnopqrstuvwxyz012345 and content://private/item"
                    )
                )
            ),
            privacyLevel = PrivacyLevel.BALANCED,
            execMode = ExecutionMode.CLOUD_ONLY
        ) as SanitizationResult.Allowed

        val cloudText = buildString {
            append(result.sanitized.prompt)
            append(result.sanitized.systemPrompt)
            result.sanitized.conversationHistory.forEach { append(it.content) }
        }
        assertFalse(cloudText.contains(rawImei))
        assertFalse(cloudText.contains(rawSerial))
        assertFalse(cloudText.contains("/data/user/0"))
        assertFalse(cloudText.contains("content://private/item"))
        assertTrue(cloudText.contains("[DEVICE_ID_REDACTED]"))
        assertTrue(cloudText.contains("[KEY_REDACTED]"))
        assertTrue(result.strippedItems.all { it in setOf(
            "path", "uri", "credential", "private_ip", "gps", "device_id", "a11y_context",
            "prompt_truncated", "system_prompt_truncated", "history_truncated"
        ) })
    }

    @Test
    fun maximumPrivacyBlocksCloudRequestWithoutReturningContent() {
        val result = PrivacyGuard.evaluate(
            request = ExecutionRequest(prompt = "private task"),
            privacyLevel = PrivacyLevel.MAXIMUM,
            execMode = ExecutionMode.HYBRID
        )

        assertTrue(result is SanitizationResult.Blocked)
    }

    @Test
    fun performancePrivacyPreservesExplicitOptInRequest() {
        val request = ExecutionRequest(
            prompt = "full context",
            conversationHistory = listOf(ExecutionRequest.ConversationTurn("user", "prior context"))
        )
        val result = PrivacyGuard.evaluate(
            request = request,
            privacyLevel = PrivacyLevel.PERFORMANCE,
            execMode = ExecutionMode.CLOUD_ONLY
        ) as SanitizationResult.Allowed

        assertEquals(request, result.sanitized)
        assertTrue(result.strippedItems.isEmpty())
    }
}
