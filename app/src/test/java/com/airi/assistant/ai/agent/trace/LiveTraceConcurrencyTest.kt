package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveTraceConcurrencyTest {
    private fun event(
        executionId: String,
        sequence: Long,
        kind: ExecutionTraceKind,
        actionId: String? = null,
        parentId: String? = null,
    ) = ExecutionTraceEvent(
        executionId = executionId,
        sequence = sequence,
        timestampMs = System.currentTimeMillis() + sequence,
        kind = kind,
        summary = "$kind-$sequence",
        actionId = actionId,
        parentId = parentId,
    )

    private fun live(events: List<ExecutionTraceEvent>) = requireNotNull(
        LiveTraceProjector.project(events, "goal", "session"),
    )

    @Test
    fun outOfOrderLiveSnapshots_doNotRegressTheVisibleTrace() {
        val manager = AgentTraceManager()
        val older = live(listOf(
            event("exec", 1, ExecutionTraceKind.PLANNING),
            event("exec", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "tool"),
        ))
        val newer = live(listOf(
            event("exec", 1, ExecutionTraceKind.PLANNING),
            event("exec", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "tool"),
            event("exec", 3, ExecutionTraceKind.TOOL_OUTPUT, actionId = "tool"),
        ))

        manager.publishLive(newer)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(32) { index ->
            pool.submit {
                start.await()
                manager.publishLive(if (index % 2 == 0) older else newer)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(2, TimeUnit.SECONDS))

        val visible = requireNotNull(manager.getTrace("trace-exec"))
        assertEquals(3L, visible.steps.maxOf { it.sequence })
        assertTrue(visible.steps.any { it.lifecycleKinds.contains(ExecutionTraceKind.TOOL_OUTPUT) })
    }

    @Test
    fun concurrentExecutions_areProjectedIndependently() {
        val manager = AgentTraceManager()
        val bridge = LiveTraceBridge(
            events = kotlinx.coroutines.flow.emptyFlow(),
            goalFor = { executionId -> "goal-$executionId" to "session-$executionId" },
            manager = manager,
        )
        bridge.handle(listOf(
            event("exec-a", 1, ExecutionTraceKind.PLANNING),
            event("exec-a", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "a-tool"),
            event("exec-b", 1, ExecutionTraceKind.PLANNING),
            event("exec-b", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "b-tool"),
        ))

        assertEquals(2, manager.traces.value.size)
        assertEquals(setOf("trace-exec-a", "trace-exec-b"), manager.traces.value.map { it.id }.toSet())
        assertEquals(setOf("goal-exec-a", "goal-exec-b"), manager.traces.value.map { it.originalInput }.toSet())
    }

    @Test
    fun liveOutput_updatesTheExistingActionNode_insteadOfAddingARow() {
        val manager = AgentTraceManager()
        val bridge = LiveTraceBridge(
            events = kotlinx.coroutines.flow.emptyFlow(),
            goalFor = { "goal" to "session" },
            manager = manager,
        )
        bridge.handle(listOf(
            event("exec", 1, ExecutionTraceKind.TOOL_REQUESTED, actionId = "tool"),
            event("exec", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "tool"),
        ))
        bridge.handle(listOf(
            event("exec", 1, ExecutionTraceKind.TOOL_REQUESTED, actionId = "tool"),
            event("exec", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "tool"),
            event("exec", 3, ExecutionTraceKind.TOOL_OUTPUT, actionId = "tool"),
        ))

        val visible = requireNotNull(manager.getTrace("trace-exec"))
        assertEquals(1, visible.steps.count { it.actionId == "tool" })
        assertEquals(
            listOf(
                ExecutionTraceKind.TOOL_REQUESTED,
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.TOOL_OUTPUT,
            ),
            visible.steps.single { it.actionId == "tool" }.lifecycleKinds,
        )
    }

    @Test
    fun timeoutAfterToolOutput_keepsOneActionAndMarksRunFailed() {
        val trace = live(listOf(
            event("exec", 1, ExecutionTraceKind.TOOL_REQUESTED, actionId = "tool"),
            event("exec", 2, ExecutionTraceKind.TOOL_STARTED, actionId = "tool"),
            event("exec", 3, ExecutionTraceKind.TOOL_OUTPUT, actionId = "tool"),
            event("exec", 4, ExecutionTraceKind.TIMED_OUT),
        ))

        val action = trace.steps.single { it.actionId == "tool" }
        assertEquals(
            listOf(
                ExecutionTraceKind.TOOL_REQUESTED,
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.TOOL_OUTPUT,
            ),
            action.lifecycleKinds,
        )
        assertFalse(trace.success)
        assertTrue(trace.steps.any {
            it.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.FAILED &&
                it.lifecycleKinds.contains(ExecutionTraceKind.TIMED_OUT)
        })
    }

    @Test
    fun cancellationBetweenRequestAndStart_isRetainedAndNotSuccessful() {
        val trace = live(listOf(
            event("exec", 1, ExecutionTraceKind.TOOL_REQUESTED, actionId = "tool"),
            event("exec", 2, ExecutionTraceKind.CANCELLED),
        ))

        assertFalse(trace.success)
        assertTrue(trace.steps.any { it.actionId == "tool" && it.lifecycleKinds == listOf(ExecutionTraceKind.TOOL_REQUESTED) })
        assertTrue(trace.steps.any { it.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.CANCELLED })
    }
}
