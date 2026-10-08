package com.airi.assistant.agent.orchestrator

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionAgentOrchestratorCancellationTest {

    @Test
    fun cancelAll_doesNotCancelTheCallingRequestScope() = runBlocking {
        val orchestrator = ProductionAgentOrchestrator()
        assertTrue(currentCoroutineContext().isActive)

        orchestrator.cancelAll()

        assertTrue(currentCoroutineContext().isActive)
    }
}
