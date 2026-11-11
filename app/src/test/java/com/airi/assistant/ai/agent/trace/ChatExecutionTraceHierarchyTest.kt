package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionTraceHierarchyTest {
    @Test
    fun `mapper keeps execution and action lifecycle events in sequence order`() {
        val trace = requireNotNull(ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec",
            sessionId = "session",
            messageId = 1L,
            userInput = "run",
            events = listOf(
                ExecutionTraceEvent("exec", 4, 104, ExecutionTraceKind.COMPLETED, summary = "done"),
                ExecutionTraceEvent("exec", 1, 101, ExecutionTraceKind.EXECUTION_STARTED, summary = "started"),
                ExecutionTraceEvent("exec", 2, 102, ExecutionTraceKind.TOOL_REQUESTED, summary = "requested", actionId = "a"),
                ExecutionTraceEvent("exec", 3, 103, ExecutionTraceKind.TOOL_OUTPUT, summary = "output", actionId = "a"),
            ),
            answerText = "done",
        ))

        assertEquals(
            listOf(1L, 3L),
            trace.steps.map { it.sequence },
        )
        assertTrue(trace.steps.any { it.lifecycleKinds.contains(ExecutionTraceKind.EXECUTION_STARTED) })
        val action = trace.steps.single { it.actionId == "a" }
        assertEquals(
            listOf(ExecutionTraceKind.TOOL_REQUESTED, ExecutionTraceKind.TOOL_OUTPUT),
            action.lifecycleKinds,
        )
    }

    @Test
    fun `parentId builds hierarchy and invalid parent is explicit`() {
        val trace = requireNotNull(ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec",
            sessionId = "session",
            messageId = 1L,
            userInput = "run",
            events = listOf(
                ExecutionTraceEvent("exec", 1, 101, ExecutionTraceKind.TOOL_STARTED, summary = "parent", actionId = "parent"),
                ExecutionTraceEvent("exec", 2, 102, ExecutionTraceKind.TOOL_STARTED, summary = "child", actionId = "child", parentId = "parent"),
                ExecutionTraceEvent("exec", 3, 103, ExecutionTraceKind.TOOL_STARTED, summary = "orphan", actionId = "orphan", parentId = "missing"),
                ExecutionTraceEvent("exec", 4, 104, ExecutionTraceKind.COMPLETED, summary = "done"),
            ),
            answerText = "done",
        ))

        val parent = trace.hierarchy.single { it.step.actionId == "parent" }
        assertEquals(listOf("child"), parent.children.map { it.step.actionId })
        val orphan = trace.steps.single { it.actionId == "orphan" }
        assertTrue(orphan.orphanedParent)
        assertTrue(orphan.name.startsWith("orphaned_parent:"))
    }

    @Test
    fun `verification started requires a passed verification before success`() {
        val trace = requireNotNull(ChatExecutionTraceMapper.toAgentTrace(
            executionId = "exec",
            sessionId = "session",
            messageId = 1L,
            userInput = "run",
            events = listOf(
                ExecutionTraceEvent("exec", 1, 101, ExecutionTraceKind.VERIFICATION_STARTED, summary = "checking", actionId = "verify"),
                ExecutionTraceEvent("exec", 2, 102, ExecutionTraceKind.COMPLETED, summary = "done"),
            ),
            answerText = "done",
        ))
        assertEquals(false, trace.success)
    }
}
