package com.airi.assistant.ai

import com.airi.assistant.ai.context.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputBudgetPolicyTest {
    @Test fun analyticalRequestsReceiveTheLargestBudget() {
        assertEquals(12288, OutputBudgetPolicy.requestedTokens("analyze this in detail", QueryType.ANALYTICAL))
    }

    @Test fun detailedSimpleConversationDoesNotUseSimpleReplyBudget() {
        assertEquals(
            8192,
            OutputBudgetPolicy.requestedTokens("حدثني عن الذكاء الاصطناعي في عصرنا الحالي", QueryType.SIMPLE),
        )
    }

    @Test fun arabicCodeCreationRequestGetsLongFormOutputBudget() {
        val input = "اصنع كود بلغة python لآلة حاسبة كاملة"
        val queryType = QueryClassifier.classifyQuery(input)
        assertEquals(QueryType.ACTION, queryType)
        assertEquals(
            10240,
            OutputBudgetPolicy.effectiveTokens(
                input,
                queryType,
                estimatedPromptTokens = 900,
                providerId = "gemini",
                budget = ContextBudget.UNLOADED,
            ),
        )
    }

    @Test fun localBudgetNeverExceedsRemainingContext() {
        val budget = ContextBudget(2048)
        val result = OutputBudgetPolicy.effectiveTokens("explain", QueryType.ANALYTICAL, 2000, "local", budget)
        assertTrue(result <= 1)
        assertTrue(result >= 1)
    }

    @Test fun cloudBudgetKeepsRequestedPolicy() {
        assertEquals(12288, OutputBudgetPolicy.effectiveTokens("analyze this in detail", QueryType.ANALYTICAL, 9000, "openai", ContextBudget.UNLOADED))
    }

    @Test fun codeTaskKeepsLongFormBudgetEvenWhenPhrasingMatchesNoDeliverableMarker() {
        // "قم بإنشاء كود ..." matches none of ResponseDepthPolicy's literal markers, so as an ACTION
        // it would get the 2,048-token STANDARD budget and truncate the write_file content.
        val input = "قم بإنشاء كود لآلة حاسبة بلغة python"
        assertEquals(OutputBudgetPolicy.CODE_TASK_TOKENS, OutputBudgetPolicy.requestedTokens(input, QueryType.ACTION))
        assertEquals(10_240, OutputBudgetPolicy.requestedTokens(input, QueryType.ACTION))
    }

    @Test fun nonCodeActionBudgetIsUnchanged() {
        assertEquals(2_048, OutputBudgetPolicy.requestedTokens("افتح الإعدادات", QueryType.ACTION))
    }
}
