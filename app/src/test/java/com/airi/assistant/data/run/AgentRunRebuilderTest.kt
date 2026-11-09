package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.run.AgentRunScreenState
import com.airi.assistant.ui.run.deriveRunScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRebuilderTest {
    private fun original(success: Boolean = true, result: String = "done"): AgentTrace = AgentTrace(
        id = "run-1", originalInput = "make a calculator\nwith themes", success = success, finalResult = result,
        timestamp = 1_000, executionId = "exec-1", sessionId = "s1", messageId = 7, model = "m1", provider = "p1",
        steps = listOf(
            AgentStep(0, AgentStepType.TOOL, "write_file", inputParams = mapOf("filename" to "calc.py", "mode" to "w"),
                outputSummary = "saved", durationMs = 40, timestampMs = 1_100),
            AgentStep(1, AgentStepType.TOOL, "run_python", outputSummary = "", success = false, error = "SyntaxError",
                timestampMs = 1_200),
        ),
    )

    private fun roundTrip(trace: AgentTrace): AgentTrace =
        AgentRunRebuilder.toTrace(AgentRunMapper.toRun(trace), AgentRunMapper.toEvents(trace))

    @Test fun stepsComeBackWithTypeNameStatusAndParams() {
        val back = roundTrip(original())
        assertEquals(2, back.steps.size)
        val first = back.steps[0]
        assertEquals(AgentStepType.TOOL, first.type)
        assertEquals("write_file", first.name)
        assertEquals(mapOf("filename" to "calc.py", "mode" to "w"), first.inputParams)
        assertEquals("saved", first.outputSummary)
        assertTrue(first.success)
        val failed = back.steps[1]
        assertFalse(failed.success)
        assertEquals("SyntaxError", failed.error)
    }

    @Test fun runLevelFieldsAreKept() {
        val back = roundTrip(original())
        assertEquals("run-1", back.id)
        assertEquals("make a calculator\nwith themes", back.originalInput)
        assertEquals("exec-1", back.executionId)
        assertEquals(7L, back.messageId)
        assertEquals("m1", back.resolvedModel)
        assertFalse(back.inProgress)
    }

    @Test fun aValueWithASemicolonInsideStaysWhole() {
        assertEquals(mapOf("cmd" to "a; b", "x" to "1"), AgentRunRebuilder.parseParams("cmd=a; b; x=1"))
        assertTrue(AgentRunRebuilder.parseParams("").isEmpty())
    }

    @Test fun anUnknownStepTypeFallsBackToTaskStep() {
        val run = AgentRunMapper.toRun(original())
        val e = AgentRunMapper.toEvents(original()).first().copy(type = "SOMETHING_NEW")
        assertEquals(AgentStepType.TASK_STEP, AgentRunRebuilder.toTrace(run, listOf(e)).steps.single().type)
    }

    @Test fun modelTurnsSurviveTheRoundTrip() {
        val turn = com.airi.assistant.ai.agent.trace.ModelTurnSteps.toStep(
            com.airi.assistant.ai.agent.trace.ModelTurnRecord(1, 1_050, 900, "p1", "m1", finishReason = "STOP"),
        )
        val back = roundTrip(original().copy(steps = listOf(turn) + original().steps))
        val view = com.airi.assistant.ai.agent.trace.ModelTurnSteps.view(back.steps.first())!!
        assertEquals("STOP", view.finishReason)
        assertEquals("m1", view.model)
    }

    @Test fun aRunLeftRunningIsShownAsInterruptedNotAsLive() {
        val live = original().copy(inProgress = true)
        val state = deriveRunScreenState("run-1", listOf(roundTrip(live)), loaded = true)
        assertTrue(state is AgentRunScreenState.Failed)
    }

    @Test fun aCancelledRunStillReadsAsCancelled() {
        val cancelled = original(success = false, result = "Task cancelled by the user").copy(steps = emptyList())
        val state = deriveRunScreenState("run-1", listOf(roundTrip(cancelled)), loaded = true)
        assertTrue(state is AgentRunScreenState.Failed && state.cancelled)
    }

    @Test fun aCompletedRunOpensAsCompleted() {
        assertTrue(deriveRunScreenState("run-1", listOf(roundTrip(original(success = true))), loaded = true) is AgentRunScreenState.Completed)
    }

    @Test fun theFullRequestAndAnswerComeBackNotJustTheTitleAndPreview() {
        val long = "line one\n" + "x".repeat(3_000)
        val back = roundTrip(original(result = long))
        assertEquals("make a calculator\nwith themes", back.originalInput)
        assertEquals(long, back.finalResult)
    }

    @Test fun textBeyondTheCapIsCutAtTheCapNotDropped() {
        val huge = "y".repeat(AgentRunMapper.FULL_TEXT_CAP + 500)
        assertEquals(AgentRunMapper.FULL_TEXT_CAP, roundTrip(original(result = huge)).finalResult.length)
    }

    @Test fun aRowFromBeforeSchemaTwoFallsBackToTitleAndPreview() {
        val old = AgentRunMapper.toRun(original(result = "done")).copy(request = "", answer = "")
        val back = AgentRunRebuilder.toTrace(old, emptyList())
        assertEquals("make a calculator", back.originalInput)
        assertEquals("done", back.finalResult)
    }
}
