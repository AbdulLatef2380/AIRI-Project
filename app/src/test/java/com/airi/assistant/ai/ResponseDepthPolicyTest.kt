package com.airi.assistant.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseDepthPolicyTest {
    @Test
    fun explicitBriefInstructionWinsOverDetailedQuestion() {
        assertEquals(
            ResponseDepth.BRIEF,
            ResponseDepthPolicy.resolve("اشرح باختصار كيف تعمل الموصلات", QueryType.ANALYTICAL),
        )
    }

    @Test
    fun shortGreetingRemainsBrief() {
        assertEquals(
            ResponseDepth.BRIEF,
            ResponseDepthPolicy.resolve("hi", QueryType.SIMPLE),
        )
    }

    @Test
    fun explanationRemainsDetailedEvenWhenRoutingIsSimple() {
        assertEquals(
            ResponseDepth.DETAILED,
            ResponseDepthPolicy.resolve("اشرح لي كيف تعمل الموصلات", QueryType.SIMPLE),
        )
    }

    @Test
    fun openEndedArabicTopicRequestIsDetailedEvenWhenRoutingIsSimple() {
        assertEquals(
            ResponseDepth.DETAILED,
            ResponseDepthPolicy.resolve("حدثني عن الذكاء الاصطناعي في عصرنا الحالي", QueryType.SIMPLE),
        )
    }

    @Test
    fun multiPartRequestIsDetailed() {
        assertEquals(
            ResponseDepth.DETAILED,
            ResponseDepthPolicy.resolve(
                "اقرأ المشروع، حلل المشكلة، ثم اقترح الإصلاحات واختبر النتيجة.",
                QueryType.SIMPLE,
            ),
        )
    }
}
