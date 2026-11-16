package com.airi.assistant.agent.request

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileWriteAdmissionTest {
    @Test
    fun executableRequiresEveryRuntimeGate() {
        val decision = FileWriteAdmission.evaluate(
            requested = true,
            modelSupport = ModelToolSupport.NATIVE,
            registered = true,
            exposed = true,
            admitted = true,
            hasDurableContext = true,
            governanceAllowed = true,
        )

        assertTrue(decision.isReady)
        assertTrue(decision.executable)
        assertEquals(FileWriteAdmission.Status.EXECUTABLE, decision.status)
        assertEquals(FileWriteAdmission.NextAction.INVOKE_WRITE_FILE, decision.nextAction)
    }

    @Test
    fun modelWithoutToolProtocolIsNeverReportedAsExecutable() {
        val decision = FileWriteAdmission.evaluate(
            requested = true,
            modelSupport = ModelToolSupport.NONE,
            registered = true,
            exposed = true,
            admitted = true,
            hasDurableContext = true,
            governanceAllowed = true,
        )

        assertFalse(decision.isReady)
        assertEquals(FileWriteAdmission.Status.MODEL_HAS_NO_TOOLS, decision.status)
        assertEquals(FileWriteAdmission.NextAction.SELECT_TOOL_CAPABLE_MODEL, decision.nextAction)
    }

    @Test
    fun requiredToolOmittedByBudgetIsDistinctFromMissingFeature() {
        val decision = FileWriteAdmission.evaluate(
            requested = true,
            modelSupport = ModelToolSupport.TEXT_PROTOCOL,
            registered = true,
            exposed = true,
            admitted = true,
            hasDurableContext = true,
            governanceAllowed = true,
            requiredToolOmittedByBudget = true,
        )

        assertEquals(FileWriteAdmission.Status.CONTEXT_BUDGET, decision.status)
        assertEquals(FileWriteAdmission.NextAction.RETRY_WITH_LARGER_CONTEXT, decision.nextAction)
    }

    @Test
    fun durableContextAndGovernanceFailuresRemainExplicit() {
        val noContext = FileWriteAdmission.evaluate(
            requested = true,
            modelSupport = ModelToolSupport.NATIVE,
            registered = true,
            exposed = true,
            admitted = false,
            hasDurableContext = false,
            governanceAllowed = true,
        )
        val denied = FileWriteAdmission.evaluate(
            requested = true,
            modelSupport = ModelToolSupport.NATIVE,
            registered = true,
            exposed = true,
            admitted = true,
            hasDurableContext = true,
            governanceAllowed = false,
        )

        assertEquals(FileWriteAdmission.Status.DURABLE_CONTEXT_REQUIRED, noContext.status)
        assertEquals(FileWriteAdmission.Status.GOVERNANCE_REQUIRED, denied.status)
    }
}
