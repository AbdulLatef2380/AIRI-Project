package com.airi.assistant.agent.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModePolicyTest {
    @Test
    fun numberedPlanWithoutToolCallRequiresExecutionContinuation() {
        val response = """
            1. اقرأ المستودع.
            2. شغّل الاختبارات.
            3. لخّص النتيجة.
        """.trimIndent()

        assertTrue(PlanModePolicy.requiresExecutionContinuation(response, step = 1))
    }

    @Test
    fun toolCallPlanDoesNotTriggerDuplicateContinuation() {
        val response = """
            1. اقرأ المستودع.
            2. نفّذ الفحص.
            {"tool_call":{"name":"terminal_execute","args":{"command":"git status"}}}
        """.trimIndent()

        assertFalse(PlanModePolicy.requiresExecutionContinuation(response, step = 1))
    }

    @Test
    fun laterPlainResponseIsNotReclassifiedAsInitialPlan() {
        assertFalse(
            PlanModePolicy.requiresExecutionContinuation(
                response = "1. النتيجة جاهزة.\n2. انتهيت.",
                step = 2,
            )
        )
    }
}
