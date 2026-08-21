package com.airi.desktop

import com.airi.core.memory.text.MemoryTextNormalizer
import com.airi.core.planning.ActionPlan
import com.airi.core.planning.AgentGoal
import com.airi.core.planning.PlanStep
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.Base64

enum class DesktopSpeaker {
    USER,
    AIRI
}

data class DesktopMessage(
    val id: String,
    val speaker: DesktopSpeaker,
    val body: String,
    val timestampMillis: Long
)

data class DesktopReply(
    val message: DesktopMessage,
    val goal: AgentGoal,
    val plan: ActionPlan
)

class DesktopConversationStore(
    private val file: Path = Path.of(System.getProperty("user.home"), ".airi-desktop", "foundation-session.log")
) {
    fun load(): List<DesktopMessage> {
        if (!Files.exists(file)) return emptyList()

        return Files.readAllLines(file, UTF_8).mapNotNull { line ->
            val parts = line.split('|', limit = 4)
            if (parts.size != 4) return@mapNotNull null

            val speaker = runCatching { DesktopSpeaker.valueOf(parts[1]) }.getOrNull()
                ?: return@mapNotNull null
            val body = runCatching {
                String(Base64.getDecoder().decode(parts[3]), UTF_8)
            }.getOrNull() ?: return@mapNotNull null
            val timestamp = parts[2].toLongOrNull() ?: return@mapNotNull null

            DesktopMessage(
                id = parts[0],
                speaker = speaker,
                body = body,
                timestampMillis = timestamp
            )
        }
    }

    fun append(message: DesktopMessage) {
        Files.createDirectories(file.parent)
        val encodedBody = Base64.getEncoder().encodeToString(message.body.toByteArray(UTF_8))
        val record = listOf(message.id, message.speaker.name, message.timestampMillis, encodedBody)
            .joinToString(separator = "|")
        Files.writeString(file, "$record\n", UTF_8, CREATE, APPEND)
    }

    fun clear() {
        Files.deleteIfExists(file)
    }
}

class DesktopAgent(
    private val conversationStore: DesktopConversationStore = DesktopConversationStore(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun history(): List<DesktopMessage> = conversationStore.load()

    fun submit(input: String): DesktopReply? {
        val request = input.trim()
        if (request.isEmpty()) return null

        val timestamp = clock()
        val userMessage = DesktopMessage(
            id = "user-$timestamp",
            speaker = DesktopSpeaker.USER,
            body = request,
            timestampMillis = timestamp
        )
        conversationStore.append(userMessage)

        val normalizedTokens = MemoryTextNormalizer.tokens(request)
        val planStep = PlanStep.Custom(
            id = "desktop-local-response",
            action = "prepare_local_response",
            parameters = mapOf("tokenCount" to normalizedTokens.size.toString()),
            expectedOutcome = "عرض استجابة محلية محفوظة"
        )
        val goal = AgentGoal(
            id = "desktop-goal-$timestamp",
            description = request,
            steps = listOf(planStep)
        )
        val plan = ActionPlan(
            intent = "معالجة طلب سطح المكتب محلياً",
            confidence = if (normalizedTokens.isEmpty()) 0.5 else 0.75,
            steps = goal.steps
        )
        val assistantMessage = DesktopMessage(
            id = "airi-${timestamp + 1}",
            speaker = DesktopSpeaker.AIRI,
            body = localResponse(normalizedTokens.size),
            timestampMillis = timestamp + 1
        )
        conversationStore.append(assistantMessage)

        return DesktopReply(
            message = assistantMessage,
            goal = goal,
            plan = plan
        )
    }

    fun clearHistory() {
        conversationStore.clear()
    }

    private fun localResponse(tokenCount: Int): String =
        "تمت معالجة الطلب محلياً عبر AIRI Core. أنشأت الخطة خطوة واحدة، " +
            "وتم حفظ السجل. عدد الرموز المطَبَّعة: $tokenCount."
}
