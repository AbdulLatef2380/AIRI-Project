package com.airi.assistant.data.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
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
}
