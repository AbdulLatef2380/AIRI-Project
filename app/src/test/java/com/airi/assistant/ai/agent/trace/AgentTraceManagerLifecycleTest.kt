package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentTraceManagerLifecycleTest {

    private fun step(i: Int) = AgentStep(stepIndex = i, type = AgentStepType.TOOL, name = "tool_$i")

    @Test
    fun stepsBeyondTheCap_areCountedAndFlaggedTruncated() {
        val manager = AgentTraceManager()
        val id = manager.startTrace("long run")
        repeat(AgentTraceManager.MAX_STEPS + 3) { manager.addStep(id, step(it)) }
        manager.finalizeTrace(id, "done", success = true)

        val trace = requireNotNull(manager.getTrace(id))
        assertEquals(AgentTraceManager.MAX_STEPS, trace.steps.size)
        assertTrue(trace.truncated)
        assertEquals(3, trace.droppedSteps)
        assertEquals("tool_${AgentTraceManager.MAX_STEPS + 2}", trace.steps.last().name)
    }

    @Test
    fun withinTheCap_traceIsNotTruncated() {
        val manager = AgentTraceManager()
        val id = manager.startTrace("short run")
        manager.addStep(id, step(0))
        manager.finalizeTrace(id, "done", success = true)

        val trace = requireNotNull(manager.getTrace(id))
        assertFalse(trace.truncated)
        assertEquals(0, trace.droppedSteps)
    }

    @Test
    fun clearTraces_doesNotDropAnInFlightTrace() {
        val manager = AgentTraceManager()
        val id = manager.startTrace("running")
        manager.clearTraces()
        manager.addStep(id, step(0))
        manager.finalizeTrace(id, "done", success = true)

        assertEquals(1, requireNotNull(manager.getTrace(id)).steps.size)
    }

    @Test
    fun cancelTrace_finalizesAsFailedAndReleasesTheBuilder() {
        val manager = AgentTraceManager()
        val id = manager.startTrace("cancelled run")
        manager.cancelTrace(id, "User cancelled")

        val trace = requireNotNull(manager.getTrace(id))
        assertFalse(trace.success)
        assertEquals("User cancelled", trace.finalResult)
        // A second finalize finds no builder and must not create a duplicate.
        manager.finalizeTrace(id, "late", success = true)
        assertEquals(1, manager.traces.value.size)
    }

    @Test
    fun stepSuccessDefaultsToTrue() {
        assertTrue(step(0).success)
    }

    @Test
    fun recordChatExecution_replacesTraceWithSameId() {
        val manager = AgentTraceManager()
        manager.recordChatExecution(AgentTrace(id = "exec-1", originalInput = "a", success = false))
        manager.recordChatExecution(AgentTrace(id = "exec-1", originalInput = "a", success = true))

        assertEquals(1, manager.traces.value.size)
        assertTrue(manager.traces.value.single().success)
    }

    @Test
    fun deleteForMessageAndSession_removeOnlyOwnedFinishedTraces() {
        val manager = AgentTraceManager()
        manager.recordChatExecution(AgentTrace(id = "m1", originalInput = "a", messageId = 1L, sessionId = "s1"))
        manager.recordChatExecution(AgentTrace(id = "m2", originalInput = "b", messageId = 2L, sessionId = "s2"))
        manager.recordChatExecution(AgentTrace(id = "m3", originalInput = "c", messageId = 3L, sessionId = "s1"))

        manager.deleteForMessage(2L)
        manager.deleteForSession("s1")

        assertTrue(manager.traces.value.isEmpty())
    }

    @Test
    fun deletingOwnerWhileTraceIsActive_preventsLateFinalizeFromRecreatingIt() {
        val manager = AgentTraceManager()
        val sessionTrace = manager.startTrace("session work", executionId = "e1", sessionId = "s1")
        val messageTrace = manager.startTrace("message work", executionId = "e2", messageId = 22L)

        manager.deleteForSession("s1")
        manager.deleteForMessage(22L)
        manager.finalizeTrace(sessionTrace, "late session result", success = true)
        manager.finalizeTrace(messageTrace, "late message result", success = true)

        assertTrue(manager.traces.value.isEmpty())
    }

    @Test
    fun clearAllTraces_abandonsActiveBuilders() {
        val manager = AgentTraceManager()
        val id = manager.startTrace("account data", executionId = "e1", sessionId = "s1")

        manager.clearAllTraces()
        manager.finalizeTrace(id, "late result", success = true)

        assertTrue(manager.traces.value.isEmpty())
    }

    @Test
    fun store_roundTripsTraceFields() {
        val file = File.createTempFile("agent_traces", ".json").apply { deleteOnExit() }
        val store = AgentTraceStore(file)
        val original = AgentTrace(
            id = "exec-7",
            traceId = "trace-7",
            originalInput = "hello",
            steps = listOf(AgentStep(0, AgentStepType.TOOL, "search", mapOf("q" to "x"), "ok", true, null, 12L)),
            finalResult = "done",
            success = true,
            executionId = "exec-7",
            messageId = 42L,
            sessionId = "s1",
            source = AgentTrace.SOURCE_CHAT,
            estimatedOutputTokens = 123,
            firstVisibleTokenLatencyMs = 850L,
            truncated = true,
            droppedSteps = 2,
        )
        store.save(listOf(original))

        val loaded = store.load().single()
        assertEquals(original, loaded.copy(timestamp = original.timestamp))
    }

    @Test
    fun store_loadOfMissingOrCorruptFile_returnsEmpty() {
        val missing = AgentTraceStore(File("/nonexistent-dir/none.json"))
        assertTrue(missing.load().isEmpty())

        val corrupt = File.createTempFile("agent_traces_bad", ".json").apply { writeText("{not json"); deleteOnExit() }
        assertTrue(AgentTraceStore(corrupt).load().isEmpty())
    }

    @Test
    fun mapper_buildsChatTraceFromTerminalEvents() {
        fun event(seq: Long, kind: ExecutionTraceKind, summary: String = "", detail: String? = null) =
            ExecutionTraceEvent("exec-9", seq, 1_000L + seq, kind, summary, detail = detail, actionId = if (kind == ExecutionTraceKind.TOOL_COMPLETED || kind == ExecutionTraceKind.TOOL_FAILED) "a$seq" else null, durationMs = 5L, parentId = "step-parent", inputParams = mapOf("query" to "safe"))

        val trace = ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec-9",
            sessionId = "s1",
            messageId = 7L,
            userInput = "find files",
            events = listOf(
                event(1, ExecutionTraceKind.PLANNING),
                event(2, ExecutionTraceKind.TOOL_STARTED),
                event(3, ExecutionTraceKind.TOOL_COMPLETED, "found 3"),
                event(4, ExecutionTraceKind.TOOL_FAILED, "read failed", "code=io"),
                event(5, ExecutionTraceKind.COMPLETED),
            ),
            answerText = "Here are your files",
            model = "gpt-4.1",
            provider = "openai",
            estimatedOutputTokens = 42,
            firstVisibleTokenLatencyMs = 710L,
        )

        assertNotNull(trace)
        trace!!
        assertEquals("exec-9", trace.id)
        assertEquals("exec-9", trace.traceId)
        assertEquals(7L, trace.messageId)
        assertEquals("traceId=exec-9|executionId=exec-9|messageId=7|sessionId=s1", trace.correlationKey)
        assertEquals(AgentTrace.SOURCE_CHAT, trace.source)
        assertEquals("gpt-4.1", trace.model)
        assertEquals("openai", trace.provider)
        assertEquals(42, trace.estimatedOutputTokens)
        assertEquals(710L, trace.firstVisibleTokenLatencyMs)
        assertTrue(trace.success)
        assertEquals(2, trace.steps.size)
        assertTrue(trace.steps[0].success)
        assertFalse(trace.steps[1].success)
        assertEquals(listOf(0, 1), trace.steps.map { it.stepIndex })
        assertEquals("step-parent", trace.steps[0].parentId)
        assertEquals(1_003L, trace.steps[0].timestampMs)
        assertEquals(mapOf("query" to "safe"), trace.steps[0].inputParams)
        assertEquals("Here are your files", trace.finalResult)
    }

    @Test
    fun mapper_failedExecutionIsMarkedUnsuccessful_andBlankInputsYieldNull() {
        val failed = ChatExecutionTraceMapper.toAgentTrace(
            "e", "s", 1L, "q",
            listOf(ExecutionTraceEvent("e", 1, 1L, ExecutionTraceKind.FAILED, "boom")),
            "ignored",
        )
        assertFalse(requireNotNull(failed).success)
        assertEquals("boom", failed.finalResult)

        val correlated = ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec-10",
            sessionId = "session-10",
            messageId = 22L,
            userInput = "q",
            events = listOf(ExecutionTraceEvent("exec-10", 1, 1L, ExecutionTraceKind.COMPLETED, "done")),
            answerText = "done",
            traceId = "trace-10",
        )
        assertEquals("trace-10", correlated?.id)
        assertEquals("trace-10", correlated?.traceId)
        assertEquals("exec-10", correlated?.executionId)
        assertEquals("traceId=trace-10|executionId=exec-10|messageId=22|sessionId=session-10", correlated?.correlationKey)

        assertNull(ChatExecutionTraceMapper.toAgentTrace("", "s", 1L, "q", emptyList(), ""))
        assertNull(ChatExecutionTraceMapper.toAgentTrace("e", "s", 1L, "q", emptyList(), ""))
    }
}
