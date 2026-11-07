package com.airi.assistant.agent.loop

import com.airi.assistant.agent.loop.tool.BuiltinTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopToolAdmissionTest {
    @Test
    fun readOnlyToolsRemainAvailableWithoutDurableContext() {
        val admitted = AgentLoopToolAdmission.filter(
            tools = listOf(BuiltinTools.WEB_SEARCH, BuiltinTools.CALENDAR_CREATE),
            hasExecutionContext = false,
            hasCalendarRuntime = false,
            terminalEnabled = true,
        )

        assertEquals(listOf("web_search"), admitted.map { it.name })
    }

    @Test
    fun calendarWriteRequiresBothTypedContextAndRuntime() {
        val calendar = listOf(BuiltinTools.CALENDAR_CREATE)
        assertTrue(AgentLoopToolAdmission.filter(calendar, true, true, true).isNotEmpty())
        assertTrue(AgentLoopToolAdmission.filter(calendar, true, false, true).isEmpty())
        assertTrue(AgentLoopToolAdmission.filter(calendar, false, true, true).isEmpty())
    }

    @Test
    fun terminalGateIsIndependentFromOtherDurableTools() {
        val tools = listOf(BuiltinTools.TERMINAL_EXECUTE, BuiltinTools.WRITE_FILE)
        val admitted = AgentLoopToolAdmission.filter(tools, true, false, false)
        assertEquals(listOf("write_file"), admitted.map { it.name })
    }
}
