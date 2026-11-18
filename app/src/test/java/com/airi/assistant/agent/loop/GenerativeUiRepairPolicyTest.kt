package com.airi.assistant.agent.loop

import com.airi.assistant.ui.generative.GenerativeUiRejectReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerativeUiRepairPolicyTest {
    private val valid = "```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"stat\",\"id\":\"s\",\"value\":\"1\"}]}\n```"
    private val duplicate = "```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"stat\",\"id\":\"a\",\"value\":\"1\"},{\"type\":\"stat\",\"id\":\"a\",\"value\":\"2\"}]}\n```"
    private val truncated = "intro\n```airi-ui\n{\"version\":1,\"blocks\":[{\"type\":\"stat\""

    @Test
    fun rejectedBlockFromCloudWithoutToolsIsRepairable() {
        assertEquals(GenerativeUiRejectReason.DUPLICATE_ID, GenerativeUiRepairPolicy.reasonToRepair(duplicate, "openai", 0))
        assertEquals(GenerativeUiRejectReason.UNCLOSED_FENCE, GenerativeUiRepairPolicy.reasonToRepair(truncated, "anthropic", 0))
    }

    @Test
    fun noRetryForValidPlainLocalToolRunOrOversizedAnswers() {
        assertNull(GenerativeUiRepairPolicy.reasonToRepair(valid, "openai", 0))
        assertNull(GenerativeUiRepairPolicy.reasonToRepair("just text", "openai", 0))
        assertNull(GenerativeUiRepairPolicy.reasonToRepair(duplicate, "local", 0))
        assertNull(GenerativeUiRepairPolicy.reasonToRepair(duplicate, "", 0))
        assertNull(GenerativeUiRepairPolicy.reasonToRepair(duplicate, "openai", 2))
        val huge = duplicate + "x".repeat(GenerativeUiRepairPolicy.MAX_ECHOED_ANSWER_CHARS)
        assertNull(GenerativeUiRepairPolicy.reasonToRepair(huge, "openai", 0))
    }

    @Test
    fun onlyNonRetryableReasonsAreExcluded() {
        assertFalse(GenerativeUiRepairPolicy.isRepairable(GenerativeUiRejectReason.NO_FENCE))
        assertFalse(GenerativeUiRepairPolicy.isRepairable(GenerativeUiRejectReason.OVERSIZE))
        GenerativeUiRejectReason.values()
            .filterNot { it == GenerativeUiRejectReason.NO_FENCE || it == GenerativeUiRejectReason.OVERSIZE }
            .forEach { assertTrue(it.name, GenerativeUiRepairPolicy.isRepairable(it)) }
    }

    @Test
    fun everyReasonHasAHintAndTheInstructionNeverEchoesModelText() {
        GenerativeUiRejectReason.values().forEach { reason ->
            assertTrue(reason.name, GenerativeUiRepairPolicy.hint(reason).isNotBlank())
            val instruction = GenerativeUiRepairPolicy.instruction(reason)
            assertTrue(instruction.contains("airi-ui"))
            assertFalse(instruction.contains("secret"))
        }
    }

    @Test
    fun repairedReplyIsAcceptedOnlyWhenValidOrCleanPlainText() {
        assertNotNull(GenerativeUiRepairPolicy.acceptRepaired("  $valid  "))
        assertEquals("plain answer", GenerativeUiRepairPolicy.acceptRepaired("plain answer"))
        assertNull(GenerativeUiRepairPolicy.acceptRepaired("   "))
        assertNull(GenerativeUiRepairPolicy.acceptRepaired(duplicate))
        assertNull(GenerativeUiRepairPolicy.acceptRepaired(truncated))
    }
}
