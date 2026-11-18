package com.airi.assistant.agent.loop

import com.airi.assistant.agent.loop.tool.BuiltinTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinToolAdmissionContractTest {
    @Test
    fun everyBuiltinIsAdmittedWithFullTypedRuntimeOrDocumentedBlocked() {
        val all = BuiltinTools.ALL.map { it.name }.toSet()
        val intentionallyBlocked = AgentLoopToolAdmission.INTENTIONALLY_BLOCKED_WITHOUT_TYPED_ROUTE
        assertTrue("blocked list contains unknown tools", (intentionallyBlocked subtract all).isEmpty())

        val admitted = AgentLoopToolAdmission.filter(
            tools = BuiltinTools.ALL,
            hasExecutionContext = true,
            hasCalendarRuntime = true,
            terminalEnabled = true,
        ).map { it.name }.toSet()
        val unexplained = all - admitted - intentionallyBlocked

        assertTrue(
            "Every registered built-in must be admitted with full context or explicitly documented; " +
                "unexplained=$unexplained",
            unexplained.isEmpty(),
        )
        assertEquals(
            "The documented blocked set must match the current admission result",
            intentionallyBlocked,
            all - admitted,
        )
    }
}
