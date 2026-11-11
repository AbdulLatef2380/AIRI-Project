package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RunTimelineModelTest {
    private fun plan(text: String) = AgentStep(0, AgentStepType.PLAN, "plan", outputSummary = text)
    private fun verify(text: String) = AgentStep(9, AgentStepType.VERIFY, "verify", outputSummary = text)
    private fun tool(name: String, ok: Boolean = true, params: Map<String, String> = emptyMap(), err: String? = null) =
        AgentStep(1, AgentStepType.TOOL, name, inputParams = params, success = ok, error = err)

    private val reqs = "- themes\n- colors\n- scientific mode\n- history\n- arabic font"

    @Test fun noPlanStepMeansNoPlanStrip() {
        assertNull(buildPlan(AgentTrace(originalInput = "x", steps = listOf(tool("a")))))
    }

    @Test fun planParsesBulletsAndCounterFromVerify() {
        val t = AgentTrace(originalInput = "x", success = true, steps = listOf(plan(reqs), verify("requirements 3/5")))
        val p = buildPlan(t)!!
        assertEquals(5, p.total)
        assertEquals(3, p.done)
        assertEquals(PlanState.NOT_STARTED, p.items[4].state)
    }

    @Test fun successfulRunWithoutReportMarksAllDone() {
        val p = buildPlan(AgentTrace(originalInput = "x", success = true, steps = listOf(plan(reqs))))!!
        assertEquals(5, p.done)
    }

    @Test fun runningPlanMarksNextItemRunning() {
        val p = buildPlan(AgentTrace(originalInput = "x", inProgress = true, steps = listOf(plan(reqs), verify("2/5"))))!!
        assertEquals(PlanState.RUNNING, p.items[2].state)
    }

    @Test fun failedRunMarksFirstUnfinishedFailed() {
        val p = buildPlan(AgentTrace(originalInput = "x", success = false, steps = listOf(plan(reqs), verify("1/5"))))!!
        assertEquals(PlanState.FAILED, p.items[1].state)
    }

    @Test fun timelineSkipsDecisionsAndExtractsSubject() {
        val t = AgentTrace(
            originalInput = "x", success = true,
            steps = listOf(plan(reqs), tool("write_file", params = mapOf("path" to "/w/calculator.py")), verify("5/5")),
        )
        val rows = buildTimeline(t)
        assertEquals(1, rows.size)
        assertEquals("calculator.py", rows[0].subject)
        assertEquals("FILE", rows[0].category)
        assertEquals(StepStatus.DONE, rows[0].status)
        assertEquals(1, buildDecisions(t).size)
    }

    @Test fun rowCountMatchesToolSteps() {
        val t = AgentTrace(originalInput = "x", steps = listOf(tool("a"), tool("b"), tool("c", ok = false, err = "boom")))
        assertEquals(3, buildTimeline(t).size)
        assertEquals(3, buildTimeline(t).map { it.id }.toSet().size)
    }

    @Test fun failedRowGetsErrorAndSuggestion() {
        val row = buildTimeline(AgentTrace(originalInput = "x", steps = listOf(tool("run_python", ok = false, err = "permission denied"))))[0]
        assertEquals(StepStatus.FAILED, row.status)
        assertNotNull(row.errorMessage)
        assertEquals(SuggestedAction.GRANT_PERMISSION, suggestedActionFor(row))
    }

    @Test fun rateLimitSuggestsSwitchModelOtherwiseRetry() {
        fun action(err: String) = suggestedActionFor(buildTimeline(AgentTrace(originalInput = "x", steps = listOf(tool("t", ok = false, err = err))))[0])
        assertEquals(SuggestedAction.SWITCH_MODEL, action("HTTP 429 rate limit"))
        assertEquals(SuggestedAction.RETRY, action("timeout"))
    }

    @Test fun lastStepOfLiveRunIsRunning() {
        val t = AgentTrace(originalInput = "x", inProgress = true, steps = listOf(tool("a"), tool("b")))
        assertEquals(listOf(StepStatus.DONE, StepStatus.RUNNING), buildTimeline(t).map { it.status })
    }
}
