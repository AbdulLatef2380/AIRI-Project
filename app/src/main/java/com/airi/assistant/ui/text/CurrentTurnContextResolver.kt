package com.airi.assistant.ui.text

/** Minimal, non-sensitive evidence projection used by the presentation layer. */
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

/** History metrics are deliberately separate from evidence belonging to the selected turn. */
data class AssistantConversationContext(
    val latestUserText: String = "",
    val messageCount: Int = 0,
    val totalChars: Int = 0,
    val hasAttachments: Boolean = false,
    val hasToolOutputs: Boolean = false,
    val hasRemoteSources: Boolean = false,
    val hasSkillsOrConnectors: Boolean = false,
    val hasFiles: Boolean = false,
    val hasImages: Boolean = false,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val hasPresentation: Boolean = false,
    val hasWebsite: Boolean = false,
    val hasApplication: Boolean = false,
    val hasGame: Boolean = false,
    val sessionAgeHours: Int? = null,
    val currentTurnMessageIds: List<Long> = emptyList(),
    val assistantMessageId: Long? = null,
) {
    init {
        require(currentTurnMessageIds.distinct().size == currentTurnMessageIds.size) {
            "current turn must not contain duplicate message ids"
        }
    }
}

object CurrentTurnContextResolver {
    fun resolve(
        messages: List<ResponseTurnMessageEvidence>,
        assistantMessageId: Long? = null,
    ): AssistantConversationContext {
        val messageCount = messages.size
        val totalChars = messages.sumOf { it.text.length }
        val assistantIndex = if (assistantMessageId != null) {
            messages.indexOfFirst { it.id == assistantMessageId && !it.isUser }
        } else {
            messages.indexOfLast { !it.isUser }
        }
        if (assistantMessageId != null && assistantIndex < 0) {
            return AssistantConversationContext(messageCount = messageCount, totalChars = totalChars)
        }
        val endExclusive = if (assistantIndex >= 0) assistantIndex + 1 else messages.size
        val userIndex = messages.subList(0, endExclusive).indexOfLast { it.isUser }
        val start = if (userIndex >= 0) userIndex else 0
        val turn = messages.subList(start, endExclusive)
        return AssistantConversationContext(
            latestUserText = turn.lastOrNull { it.isUser }?.text.orEmpty(),
            messageCount = messageCount,
            totalChars = totalChars,
            hasAttachments = turn.any { it.hasAttachments },
            hasToolOutputs = turn.any { it.hasToolOutputs },
            hasRemoteSources = turn.any { it.hasRemoteSources },
            hasSkillsOrConnectors = turn.any { it.hasSkillsOrConnectors },
            hasFiles = turn.any { it.hasFiles },
            hasImages = turn.any { it.hasImages },
            hasAudio = turn.any { it.hasAudio },
            hasVideo = turn.any { it.hasVideo },
            currentTurnMessageIds = turn.map { it.id },
            assistantMessageId = turn.lastOrNull { !it.isUser }?.id,
        )
    }
}
