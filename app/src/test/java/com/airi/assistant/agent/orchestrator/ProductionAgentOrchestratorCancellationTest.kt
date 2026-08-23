package com.airi.assistant.agent.orchestrator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAgentOrchestratorCancellationTest {

    @Test
    fun cancelAll_replacesCancelledScopeForFuturePlans() {
        val orchestrator = ProductionAgentOrchestrator()
        val field = ProductionAgentOrchestrator::class.java.getDeclaredField("orchestrationScope")
            .apply { isAccessible = true }

        val before = field.get(orchestrator) as CoroutineScope
        assertTrue(before.isActive())

        orchestrator.cancelAll()

        val after = field.get(orchestrator) as CoroutineScope
        assertNotSame(before, after)
        assertFalse(before.isActive())
        assertTrue(after.isActive())
    }

    private fun CoroutineScope.isActive(): Boolean =
        coroutineContext[Job]?.isActive == true
}
