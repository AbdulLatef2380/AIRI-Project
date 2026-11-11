package com.airi.assistant.ai.agent.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.core.ExecutionTraceKind.COMPLETED
import com.airi.assistant.core.ExecutionTraceKind.RECOVERING
import com.airi.assistant.core.ExecutionTraceKind.TOOL_COMPLETED
import com.airi.assistant.core.ExecutionTraceKind.TOOL_FAILED
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The success flag of a chat trace must never hide an unhandled failure (closure plan, phase 4.4). */
class ChatExecutionTraceSuccessRuleTest {
    private fun run(vararg kinds: ExecutionTraceKind): Boolean {
        val events = kinds.mapIndexed { i, kind ->
            ExecutionTraceEvent("e", i + 1L, 1_000L + i, kind, summary = "s", actionId = "a$i")
        }
        return requireNotNull(
            ChatExecutionTraceMapper.toAgentTrace("e", "s", 1L, "q", events, "answer")
        ).success
    }

    @Test fun cleanRunSucceeds() = assertTrue(run(TOOL_COMPLETED, COMPLETED))

    @Test fun failureThenSuccessWithoutRecoveryIsNotSuccess() =
        assertFalse(run(TOOL_FAILED, TOOL_COMPLETED, COMPLETED))

    @Test fun failureThenExplicitRecoveryIsSuccess() =
        assertTrue(run(TOOL_FAILED, RECOVERING, TOOL_COMPLETED, COMPLETED))

    @Test fun successThenFinalFailureIsNotSuccess() =
        assertFalse(run(TOOL_COMPLETED, TOOL_FAILED, COMPLETED))

    @Test fun noTerminalEventIsNotSuccess() = assertFalse(run(TOOL_COMPLETED))
}
