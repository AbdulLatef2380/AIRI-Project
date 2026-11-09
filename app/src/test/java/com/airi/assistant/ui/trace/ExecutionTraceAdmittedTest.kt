package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The agent-log detail screen uses this to choose the unified timeline or the raw-step fallback. */
class ExecutionTraceAdmittedTest {

    private fun ev(seq: Long, kind: ExecutionTraceKind, summary: String, actionId: String? = null, exec: String = "run-1") =
        ExecutionTraceEvent(
            executionId = exec,
            sequence = seq,
            timestampMs = 1_000L + seq * 100,
            kind = kind,
            summary = summary,
            actionId = actionId,
        )

    @Test
    fun runWithAToolIsAdmitted() {
        val events = listOf(
            ev(1, ExecutionTraceKind.PLANNING, "Planning task"),
            ev(2, ExecutionTraceKind.TOOL_STARTED, "Read mail", "mail-1"),
            ev(3, ExecutionTraceKind.TOOL_COMPLETED, "Read mail", "mail-1"),
        )
        assertTrue(executionTraceAdmitted("run-1", events))
    }

    @Test
    fun planningOnlyRunIsNotAdmitted() {
        val events = listOf(ev(1, ExecutionTraceKind.PLANNING, "Planning task"))
        assertFalse(executionTraceAdmitted("run-1", events))
    }

    @Test
    fun eventsOfAnotherExecutionAreIgnored() {
        val events = listOf(ev(1, ExecutionTraceKind.TOOL_COMPLETED, "Read mail", "mail-1", exec = "other"))
        assertFalse(executionTraceAdmitted("run-1", events))
    }

    @Test
    fun blankExecutionIdIsNotAdmitted() {
        val events = listOf(ev(1, ExecutionTraceKind.TOOL_COMPLETED, "Read mail", "mail-1"))
        assertFalse(executionTraceAdmitted("", events))
    }
}
