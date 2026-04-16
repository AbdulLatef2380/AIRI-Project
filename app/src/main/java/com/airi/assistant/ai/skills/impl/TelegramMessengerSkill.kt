package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage

class TelegramMessengerSkill(private val context: Context) : AiriSkill {

    override val name = "telegram_messenger"
    override val description = "Send messages using the connected Telegram bot"
    override val parameters: Map<String, String> = mapOf(
        "chat_id" to "Telegram chat ID or username",
        "text"    to "Message text to send"
    )

    private val toolExecutor  = ToolExecutor(context)
    private val secureStorage = SecureStorage(context)

    private val exactPhrases = listOf(
        "send message on telegram", "telegram message", "via telegram",
        "through telegram", "send to telegram", "message on telegram"
    )
    private val singleWords = listOf("telegram")

    override fun score(input: String, context: SkillContext): Int {
        if (!secureStorage.isTelegramConnected()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.contains("telegram") == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!secureStorage.isTelegramConnected()) {
            return SkillResult(false, "", "Telegram is not connected. Please connect it in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext

        val chatId = params["chat_id"] as? String
            ?: inferChatId(input, context)

        val text = params["text"] as? String
            ?: inferText(input, context)

        if (chatId.isNullOrBlank() || text.isNullOrBlank()) {
            return SkillResult(
                false, "",
                "Please specify a recipient and message. " +
                "Example: 'Send on Telegram to @username: Hello!'"
            )
        }

        val r = toolExecutor.execute(
            ToolCall("telegram_send_message", mapOf("chat_id" to chatId, "text" to text))
        )
        return SkillResult(r.success, r.data, r.error)
    }

    private fun inferChatId(input: String, context: SkillContext?): String? {
        val lower = input.lowercase()
        val toIdx = lower.indexOf(" to ")
        if (toIdx >= 0) {
            val afterTo = input.substring(toIdx + 4).trim()
            val candidate = afterTo.split(":", " ").firstOrNull()?.trim()
            if (!candidate.isNullOrBlank()) return candidate
        }
        val lastMsg = context?.lastAssistantMessage ?: return null
        val atIdx = lastMsg.indexOf("@")
        if (atIdx >= 0) {
            return lastMsg.substring(atIdx).split(" ", ",", "\n").firstOrNull()?.trim()
        }
        return null
    }

    private fun inferText(input: String, context: SkillContext?): String? {
        val colonIdx = input.indexOf(":")
        if (colonIdx >= 0 && colonIdx < input.length - 1) {
            return input.substring(colonIdx + 1).trim().takeIf { it.isNotBlank() }
        }
        return context?.lastAssistantMessage?.take(200)
    }
}
