package com.airi.assistant.ui.text

/** Minimal evidence projection used by the presentation layer. */
data class ResponseTurnMessageEvidence(
    val id: Long,
    val isUser: Boolean,
    val text: String,
    val hasAttachments: Boolean = false,
    val hasToolOutputs: Boolean = false,
    val hasRemoteSources: Boolean = false,
    val hasSkillsOrConnectors: Boolean = false,
    val hasFiles: Boolean = false,
    val hasImages: Boolean = false,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
)

/** Resolves current-turn evidence while retaining history only for density. */
object CurrentTurnContextResolver {
    fun resolve(
        messages: List<ResponseTurnMessageEvidence>,
        assistantMessageId: Long? = null,
    ): AssistantConversationContext {
        val assistantIndex = if (assistantMessageId != null) {
            messages.indexOfFirst { it.id == assistantMessageId && !it.isUser }
        } else {
            messages.indexOfLast { !it.isUser }
        }
        if (assistantMessageId != null && assistantIndex < 0) {
            return AssistantConversationContext(
                messageCount = messages.size,
                totalChars = messages.sumOf { it.text.length },
            )
        }
        val endExclusive = if (assistantIndex >= 0) assistantIndex + 1 else messages.size
        val userIndex = messages.subList(0, endExclusive).indexOfLast { it.isUser }
        val start = if (userIndex >= 0) userIndex else 0
        val turn = messages.subList(start, endExclusive)
        return AssistantConversationContext(
            latestUserText = turn.lastOrNull { it.isUser }?.text.orEmpty(),
            messageCount = messages.size,
            totalChars = messages.sumOf { it.text.length },
            hasAttachments = turn.any { it.hasAttachments },
            hasToolOutputs = turn.any { it.hasToolOutputs },
            hasRemoteSources = turn.any { it.hasRemoteSources },
            hasSkillsOrConnectors = turn.any { it.hasSkillsOrConnectors },
            hasFiles = turn.any { it.hasFiles },
            hasImages = turn.any { it.hasImages },
            hasAudio = turn.any { it.hasAudio },
            hasVideo = turn.any { it.hasVideo },
        )
    }
}
***
