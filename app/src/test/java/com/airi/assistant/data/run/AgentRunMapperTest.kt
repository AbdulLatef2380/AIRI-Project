package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.core.AgentEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunMapperTest {
    private val trace = AgentTrace(
        id = "run1", originalInput = "\nBuild calculator\nmore", success = true, finalResult = "Done **ok**",
        model = "m", provider = "p",
        steps = listOf(
            AgentStep(0, AgentStepType.PLAN, "plan"),
            AgentStep(1, AgentStepType.TOOL, "write_file", mapOf("path" to "calculator.py"), "x".repeat(2000), durationMs = 40, parentId = "p0"),
            AgentStep(2, AgentStepType.TOOL, "run_python", success = false, error = "bad"),
        ),
    )

    @Test fun runHeaderCountsAndTitle() {
        val run = AgentRunMapper.toRun(trace)
        assertEquals("Build calculator", run.title)
        assertEquals("COMPLETED", run.status)
        assertEquals(1, run.fileCount)
        assertEquals(1, run.commandCount)
        assertEquals("m", run.model)
    }

    @Test fun eventsKeepOrderParentAndCapPreviews() {
        val events = AgentRunMapper.toEvents(trace, previewCap = 100)
        assertEquals(listOf(0L, 1L, 2L), events.map { it.sequence })
        assertEquals("p0", events[1].parentId)
        assertEquals(100, events[1].outputPreview.length)
        assertEquals(events.size, events.map { it.eventId }.toSet().size)
        assertEquals("FAILED", events[2].status)
        assertEquals("tool_error", events[2].errorCode)
        assertNull(events[1].blobRef)
    }

    @Test fun runningTraceHasNoEndTime() {
        val live = trace.copy(inProgress = true)
        assertNull(AgentRunMapper.toRun(live).endedAtMs)
        assertEquals("RUNNING", AgentRunMapper.toRun(live).status)
    }

    @Test fun cancelledWhenNoErrorsAndMarker() {
        val c = AgentTrace(originalInput = "x", success = false, finalResult = "Cancelled")
        assertTrue(AgentRunMapper.status(c) == "CANCELLED")
    }

    @Test fun fullRequestAndAnswerAreStoredAlongsideTheTitleAndPreview() {
        val run = AgentRunMapper.toRun(trace)
        assertEquals("\nBuild calculator\nmore", run.request)
        assertEquals("Done **ok**", run.answer)
    }

    @Test fun liveEventUsesStableTraceIdAndCapsDetails() {
        val event = AgentEvent(
            eventId = "exec-1:tool:1",
            executionId = "exec-1",
            sessionId = "session-1",
            sequence = 4,
            timestampMs = 100,
            type = AgentEvent.Type.TOOL_RESULT,
            title = "write_file",
            detail = "x".repeat(50),
        )
        val row = AgentRunMapper.toEvent(event, previewCap = 10)
        assertEquals("trace-exec-1", row.runId)
        assertEquals(10, row.outputPreview.length)
        assertEquals("COMPLETED", row.status)
    }

    @Test fun finalLiveEventCarriesFailureWithoutSyntheticSuccess() {
        val event = AgentEvent(
            eventId = "exec-1:final",
            executionId = "exec-1",
            sessionId = "session-1",
            sequence = 5,
            timestampMs = 200,
            type = AgentEvent.Type.FINAL,
            title = "Failed",
            errorCode = "TOOL_REQUIRED_NOT_CALLED",
        )
        assertEquals("FAILED", AgentRunMapper.toEvent(event).status)
        assertEquals("FAILED", AgentRunMapper.liveRun(event).status)
    }

    @Test fun failedTraceKindCannotBecomeCompletedWhenErrorCodeIsMissing() {
        val event = AgentEvent(
            eventId = "exec-2:failed",
            executionId = "exec-2",
            sessionId = "session-2",
            sequence = 9,
            timestampMs = 900,
            type = AgentEvent.Type.FINAL,
            title = "Execution failed",
            traceKind = ExecutionTraceKind.FAILED,
        )

        val run = AgentRunMapper.liveRun(event)
        assertEquals("FAILED", run.status)
        assertEquals(900L, run.endedAtMs)
    }
}
