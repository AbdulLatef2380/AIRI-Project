package com.airi.assistant.ai.skills

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillToolResultFormatterTest {
    @Test
    fun preservesStatusDataMetadataAndNestedToolOutputs() {
        val text = SkillToolResultFormatter.format(
            SkillResult(
                success = true,
                data = "found two items",
                skillName = "drive_search",
                metadata = mapOf("count" to "2", "result_verified" to "true"),
                toolOutputs = listOf(SkillResult.ToolOutput("drive_api", "2 matches"))
            )
        )
        assertTrue(text.contains("status=success"))
        assertTrue(text.contains("found two items"))
        assertTrue(text.contains("count=2"))
        assertTrue(text.contains("tool=drive_api"))
        assertTrue(text.contains("2 matches"))
    }

    @Test
    fun preservesFailureSemanticsAndRedactsSecretMetadata() {
        val text = SkillToolResultFormatter.format(
            SkillResult(false, "", error = "permission denied", metadata = mapOf("api_key" to "secret", "reason" to "missing permission"))
        )
        assertTrue(text.contains("status=error"))
        assertTrue(text.contains("permission denied"))
        assertTrue(text.contains("reason=missing permission"))
        assertFalse(text.contains("secret"))
    }

    @Test
    fun outputIsBounded() {
        val text = SkillToolResultFormatter.format(SkillResult(true, "x".repeat(40_000)))
        assertTrue(text.length <= 12_000)
    }

    @Test
    fun redactsSecretsEmbeddedInFreeTextAndWebhookUrls() {
        val text = SkillToolResultFormatter.format(
            SkillResult(
                success = false,
                data = "api_key=inline-secret Bearer bearer-secret",
                error = "request failed with token=error-secret",
                toolOutputs = listOf(
                    SkillResult.ToolOutput(
                        toolName = "http",
                        output = "https://maker.ifttt.com/trigger/event/with/key/ifttt-secret"
                    )
                )
            )
        )
        assertFalse(text.contains("inline-secret"))
        assertFalse(text.contains("bearer-secret"))
        assertFalse(text.contains("error-secret"))
        assertFalse(text.contains("ifttt-secret"))
        assertTrue(text.contains("[REDACTED]"))
    }
}
