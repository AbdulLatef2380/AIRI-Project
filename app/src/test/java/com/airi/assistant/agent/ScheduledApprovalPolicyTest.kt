package com.airi.assistant.agent

import com.airi.assistant.agent.scheduler.ScheduledApprovalPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledApprovalPolicyTest {
    @Test fun confirmHoldsAutomaticWrites() =
        assertTrue(ScheduledApprovalPolicy.shouldHold("CONFIRM", isManualRun = false, requiresWrite = true))

    @Test fun confirmHoldsUnclassifiedTargets() =
        assertTrue(ScheduledApprovalPolicy.shouldHold("CONFIRM", isManualRun = false, requiresWrite = null))

    @Test fun confirmLetsReadOnlyRun() =
        assertFalse(ScheduledApprovalPolicy.shouldHold("CONFIRM", isManualRun = false, requiresWrite = false))

    @Test fun manualRunIsTheApproval() =
        assertFalse(ScheduledApprovalPolicy.shouldHold("CONFIRM", isManualRun = true, requiresWrite = true))

    @Test fun skipNeverHolds() =
        assertFalse(ScheduledApprovalPolicy.shouldHold("SKIP", isManualRun = false, requiresWrite = true))

    @Test fun unknownModeFallsBackToConfirm() {
        assertTrue(ScheduledApprovalPolicy.shouldHold("garbage", isManualRun = false, requiresWrite = true))
        assertTrue(ScheduledApprovalPolicy.shouldHold(null, isManualRun = false, requiresWrite = true))
    }
}
