package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 4, B6: readable step names, kept tool detail, and the gap marker for dropped steps. */
class ChatExecutionTraceStepDetailTest {

    private fun toolDone(seq: Long, tool: String, detail: String? = null, params: Map<String, String> = emptyMap()) =
        ExecutionTraceEvent(
            executionId = "exec-1",
            sequence = seq,
            timestampMs = 1_000L + seq,
            kind = ExecutionTraceKind.TOOL_COMPLETED,
            summary = "Tool completed: $tool",
            detail = detail,
            actionId = "action-$seq",
            machineTag = "tool_call",
            inputParams = params,
        )

    private fun completed(seq: Long) = ExecutionTraceEvent(
        executionId = "exec-1", sequence = seq, timestampMs = 9_000L,
        kind = ExecutionTraceKind.COMPLETED, summary = "done",
    )

    private fun map(events: List<ExecutionTraceEvent>) = requireNotNull(
        ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec-1", sessionId = "s", messageId = 7L,
            userInput = "hi", events = events, answerText = "ok",
        )
    )

    @Test
    fun toolStepIsNamedAfterTheToolNotTheOpaqueActionId() {
        val trace = map(listOf(toolDone(1, "write_file"), completed(2)))
        assertEquals("write_file", trace.steps.single().name)
    }

    @Test
    fun successfulToolStepKeepsItsDetailInsteadOfTheGenericSummary() {
        val detail = "Source: builtin; result length: 120 chars."
        val trace = map(listOf(toolDone(1, "read_file", detail), completed(2)))
        assertEquals(detail, trace.steps.single().outputSummary)
    }

    @Test
    fun successfulToolStepWithoutDetailFallsBackToTheSummary() {
        val trace = map(listOf(toolDone(1, "read_file"), completed(2)))
        assertEquals("Tool completed: read_file", trace.steps.single().outputSummary)
    }

    @Test
    fun boundedToolOutputEvidenceIsPreferredOverGenericCompletionDetail() {
        val done = toolDone(2, "read_file", detail = "Source: builtin; result length: 40 chars.")
        val output = ExecutionTraceEvent(
            executionId = "exec-1",
            sequence = 1,
            timestampMs = 1_001L,
            kind = ExecutionTraceKind.TOOL_OUTPUT,
            summary = "Tool output: read_file",
            actionId = "action-2",
            outputSummary = "README contains 3 sections",
        )
        val trace = map(listOf(output, done, completed(3)))
        assertEquals("README contains 3 sections", trace.steps.single().outputSummary)
    }

    @Test
    fun inputParamsReachTheStepSoTheFileCardCanFindTheFilename() {
        val trace = map(listOf(toolDone(1, "write_file", params = mapOf("filename" to "a.py")), completed(2)))
        assertEquals("a.py", trace.steps.single().inputParams["filename"])
    }

    @Test
    fun overTheCapKeepsTheFirstStepAndMarksTheGapAfterIt() {
        val total = AgentTraceManager.MAX_STEPS + 2
        val events = (1..total).map { toolDone(it.toLong(), "tool_$it") } + completed(total + 1L)
        val trace = map(events)
        assertEquals(AgentTraceManager.MAX_STEPS, trace.steps.size)
        assertTrue(trace.truncated)
        assertEquals(2, trace.droppedSteps)
        assertEquals(1, trace.droppedAtIndex)
        assertEquals("tool_1", trace.steps.first().name)
        assertEquals("tool_$total", trace.steps.last().name)
    }

    @Test
    fun withinTheCapThereIsNoGapMarker() {
        val trace = map(listOf(toolDone(1, "a_tool"), completed(2)))
        assertFalse(trace.truncated)
        assertEquals(0, trace.droppedAtIndex)
    }

    @Test
    fun droppedAtIndexSurvivesTheStoreRoundTrip() {
        val original = AgentTrace(
            id = "t", originalInput = "x", truncated = true, droppedSteps = 4, droppedAtIndex = 1,
        )
        val json = JSONObject(AgentTraceStore.traceToJson(original).toString())
        assertEquals(1, AgentTraceStore.traceFromJson(json).droppedAtIndex)
    }

    @Test
    fun olderStoredTracesWithoutTheFieldDefaultToZero() {
        val json = AgentTraceStore.traceToJson(AgentTrace(id = "t", originalInput = "x"))
        json.remove("droppedAtIndex")
        assertEquals(0, AgentTraceStore.traceFromJson(json).droppedAtIndex)
    }

    @Test
    fun toolNameFromRejectsTextThatIsNotAToolName() {
        assertEquals("write_file", ChatExecutionTraceMapper.toolNameFrom("Tool failed: write_file"))
        assertNull(ChatExecutionTraceMapper.toolNameFrom("Tool completed"))
        assertNull(ChatExecutionTraceMapper.toolNameFrom("Tool completed: two words"))
        assertNull(ChatExecutionTraceMapper.toolNameFrom("Tool completed: "))
    }
}
