package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTraceBridgeTest {

    private fun ev(
        seq: Long,
        kind: ExecutionTraceKind,
        exec: String = "exec-1",
        summary: String = "s$seq",
        actionId: String? = if (kind == ExecutionTraceKind.TOOL_COMPLETED) "read_file" else null,
    ) =
        ExecutionTraceEvent(
            executionId = exec,
            sequence = seq,
            timestampMs = 1_000L + seq,
            kind = kind,
            summary = summary,
            actionId = actionId,
        )

    private val planning = ev(1, ExecutionTraceKind.PLANNING)
    private val toolDone = ev(2, ExecutionTraceKind.TOOL_COMPLETED)
    private val completed = ev(3, ExecutionTraceKind.COMPLETED)

    private fun bridge(manager: AgentTraceManager) =
        LiveTraceBridge(
            events = kotlinx.coroutines.flow.emptyFlow(),
            goalFor = { "do the thing" to "session-1" },
            manager = manager,
        )

    @Test
    fun project_usesTheSameIdAsTheFinalChatRecord() {
        val live = requireNotNull(LiveTraceProjector.project(listOf(planning, toolDone), "goal", "s"))
        assertEquals("trace-exec-1", live.id)
        assertTrue(live.inProgress)
        assertNull(live.messageId)
        assertEquals(1, live.steps.size)
    }

    @Test
    fun project_marksTraceFinishedOnceATerminalEventArrives() {
        val live = requireNotNull(LiveTraceProjector.project(listOf(planning, toolDone, completed), "goal", "s"))
        assertFalse(live.inProgress)
        assertTrue(live.success)
    }

    @Test
    fun project_returnsNullWithoutEvents() {
        assertNull(LiveTraceProjector.project(emptyList(), "goal", "s"))
    }

    @Test
    fun bridge_showsARunOnlyOnceItHasAStep() {
        val manager = AgentTraceManager()
        val b = bridge(manager)
        b.handle(listOf(planning))
        assertTrue(manager.traces.value.isEmpty())

        b.handle(listOf(planning, toolDone))
        val shown = manager.traces.value.single()
        assertTrue(shown.inProgress)
        assertEquals("do the thing", shown.originalInput)
    }

    @Test
    fun bridge_updatesTheSameEntryInsteadOfAddingDuplicates() {
        val manager = AgentTraceManager()
        val b = bridge(manager)
        b.handle(listOf(planning, toolDone))
        b.handle(listOf(
            planning,
            toolDone,
            ev(3, ExecutionTraceKind.TOOL_COMPLETED, actionId = "read_file:second"),
            ev(4, ExecutionTraceKind.COMPLETED),
        ))
        val only = manager.traces.value.single()
        assertEquals(2, only.steps.size)
        assertFalse(only.inProgress)
    }

    @Test
    fun finalRecord_replacesTheLiveSnapshot() {
        val manager = AgentTraceManager()
        val events = listOf(planning, toolDone, completed)
        bridge(manager).handle(events.take(2))
        val final = requireNotNull(
            ChatExecutionTraceMapper.toAgentTrace("exec-1", "session-1", 42L, "do the thing", events, "answer", traceId = "trace-exec-1")
        )
        manager.recordChatExecution(final)

        val only = manager.traces.value.single()
        assertEquals(42L, only.messageId)
        assertFalse(only.inProgress)
    }

    @Test
    fun lateLiveUpdate_doesNotOverwriteTheFinalRecord() {
        val manager = AgentTraceManager()
        val events = listOf(planning, toolDone, completed)
        val final = requireNotNull(
            ChatExecutionTraceMapper.toAgentTrace("exec-1", "session-1", 42L, "do the thing", events, "answer", traceId = "trace-exec-1")
        )
        manager.recordChatExecution(final)

        bridge(manager).handle(events)

        assertEquals(42L, manager.traces.value.single().messageId)
    }

    @Test
    fun removeLiveTrace_keepsFinishedTraces() {
        val manager = AgentTraceManager()
        val events = listOf(planning, toolDone, completed)
        val final = requireNotNull(
            ChatExecutionTraceMapper.toAgentTrace("exec-1", "s", 7L, "q", events, "a", traceId = "trace-exec-1")
        )
        manager.recordChatExecution(final)
        manager.removeLiveTrace("trace-exec-1")
        assertNotNull(manager.getTrace("trace-exec-1"))
    }
}
