package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.core.ServiceLocator

class TelegramMessengerSkill(@Suppress("UNUSED_PARAMETER") context: Context) : AiriSkill {

    override val name = "telegram_messenger"
    override val description = "Telegram sending requires a typed approval continuation"
    override val parameters: Map<String, String> = mapOf(
        "chat_id" to "Telegram chat ID or username",
        "text" to "Message text to send",
    )

    private val exactPhrases = listOf(
        "send message on telegram", "telegram message", "via telegram",
        "through telegram", "send to telegram", "message on telegram",
    )
    private val singleWords = listOf("telegram")

    override fun score(input: String, context: SkillContext): Int {
        if (!isTelegramReady()) return 0
        val lower = input.lowercase()
        var score = 0
        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach { if (lower.contains(it)) score += 15 }
        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.contains("telegram") == true) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!isTelegramReady()) {
            return SkillResult(false, "", "Telegram is not connected or ready in the connector runtime.")
        }
        return SkillResult(
            success = false,
            data = "",
            error = "Telegram sending requires a typed user-approval continuation that is not available in this chat path. No message was sent.",
        )
    }

    private fun isTelegramReady(): Boolean = runCatching {
        ServiceLocator.connectorRegistry.get("telegram")?.state()?.value?.let { it.connected && it.healthy } ?: false
    }.getOrDefault(false)
}
