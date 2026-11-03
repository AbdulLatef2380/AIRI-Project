package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.QueryType
import com.airi.assistant.ai.prompt.budget.ContributorBudgetPolicy
import com.airi.assistant.domain.intent.ChatIntent

/**
 * Report 1, stage 10 (items 2-4): a plain question ("ما آخر إصدار من ...؟",
 * "كم الساعة في طوكيو؟") used to get an empty tool list unless a keyword
 * detector fired, so the model could never look anything up and behaved like
 * a closed chat. For remote providers (large context, tool-calling capable)
 * such turns now keep a tiny, fixed set of read-only tools and the model
 * decides whether to use them.
 *
 * Deliberately narrow:
 *  - only SIMPLE questions (greetings, chatter and creative turns stay tool-free);
 *  - only remote providers: the on-device window is too small to spend on a
 *    tool block for every question, which would also add to latency;
 *  - only these named, read-only, non-dangerous tools. Anything else still
 *    needs the explicit capability detection path and its admission rules.
 */
object QuestionToolExposurePolicy {
    val READ_ONLY_QUESTION_TOOLS: Set<String> = setOf("current_time", "web_search", "memory_recall")

    /**
     * Report 1, item 2 (stage 29): tools the on-device model may keep for a plain question.
     * Offline-safe and tiny: no `web_search`, since the device may have no network and the
     * schema is the largest of the three.
     */
    val LOCAL_QUESTION_TOOLS: Set<String> = setOf("current_time", "memory_recall")

    /** Minimum free content window (tokens) before the device model gets any tool block. */
    const val LOCAL_MIN_AVAILABLE_TOKENS = 3_072

    private fun forProvider(providerId: String, localAvailableTokens: Int, tools: List<ToolSchema>): List<ToolSchema> {
        val allowed = if (ContributorBudgetPolicy.isLocalProvider(providerId)) {
            if (localAvailableTokens < LOCAL_MIN_AVAILABLE_TOKENS) return emptyList()
            LOCAL_QUESTION_TOOLS
        } else READ_ONLY_QUESTION_TOOLS
        return tools.filter { it.name in allowed && !it.dangerous }
    }

    fun select(
        kind: ChatIntent.Kind,
        queryType: QueryType,
        providerId: String,
        availableTools: List<ToolSchema>,
        localAvailableTokens: Int = 0,
    ): List<ToolSchema> {
        if (kind != ChatIntent.Kind.QUESTION || queryType != QueryType.SIMPLE) return emptyList()
        return forProvider(providerId, localAvailableTokens, availableTools)
    }

    /**
     * Report 1, item 4 (stage 23): a request the classifier could not place (UNKNOWN),
     * with no detected capability and no skill picked by the user, used to carry every
     * exposed tool, skill and the 8-step interactive profile. It now gets the same
     * narrow read-only set as a plain question on remote providers and no tools on
     * the device model. A request that names a capability, or a skill the user chose,
     * is not affected: those still take the full path.
     */
    fun selectUnclassified(
        queryType: QueryType,
        capabilityRequiresTools: Boolean,
        userSelectedSkills: Boolean,
        providerId: String,
        availableTools: List<ToolSchema>,
        localAvailableTokens: Int = 0,
    ): List<ToolSchema>? {
        if (queryType != QueryType.UNKNOWN || capabilityRequiresTools || userSelectedSkills) return null
        return forProvider(providerId, localAvailableTokens, availableTools)
    }
}
