package com.airi.assistant.ui.plan

import com.airi.assistant.ui.viewmodel.ExecutionStage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanPanelVisibilityPolicyTest {
    @Test
    fun simpleRequestDoesNotOpenPanel() {
        assertFalse(
            PlanPanelVisibilityPolicy.shouldShow(
                stage = ExecutionStage.EXECUTING,
                nodesTotal = 1,
                stepCount = 1
            )
        )
    }

    @Test
    fun multiStepExecutionOpensPanelOnlyDuringRelevantStages() {
        assertTrue(
            PlanPanelVisibilityPolicy.shouldShow(
                stage = ExecutionStage.PLANNING,
                nodesTotal = 3,
                stepCount = 3
            )
        )
        assertTrue(
            PlanPanelVisibilityPolicy.shouldShow(
                stage = ExecutionStage.EXECUTING,
                nodesTotal = 3,
                stepCount = 3
            )
        )
        assertFalse(
            PlanPanelVisibilityPolicy.shouldShow(
                stage = ExecutionStage.IDLE,
                nodesTotal = 3,
                stepCount = 3
            )
        )
    }

    @Test
    fun explicitPlanCanOpenBeforeStepsExist() {
        assertTrue(
            PlanPanelVisibilityPolicy.shouldShow(
                stage = ExecutionStage.PLANNING,
                nodesTotal = 0,
                stepCount = 0,
                explicitlyPlanned = true
            )
        )
    }
}
