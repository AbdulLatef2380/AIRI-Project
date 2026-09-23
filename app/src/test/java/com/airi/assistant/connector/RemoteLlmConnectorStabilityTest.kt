package com.airi.assistant.connector

import com.airi.assistant.connector.api.RemoteLlmConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLlmConnectorStabilityTest {
    @Test
    fun executeAfterDisconnectDoesNotCallProvider() = runBlocking {
        val provider = FakeProvider()
        val connector = RemoteLlmConnector(providers = listOf(provider))
        connector.connect()
        connector.disconnect()

        val output = connector.execute(ConnectorInput(action = "chat", text = "hello"))

        assertEquals("not_connected", (output as ConnectorOutput.Failure).code)
        assertEquals(0, provider.calls)
    }

    @Test
    fun cancellationDoesNotAdvanceToTheNextProvider() = runBlocking {
        val first = FakeProvider(block = true)
        val second = FakeProvider()
        val connector = RemoteLlmConnector(providers = listOf(first, second))
        connector.connect()
        val job = launch {
            connector.execute(ConnectorInput(action = "chat", text = "hello"))
        }

        first.started.await()
        job.cancel()
        job.join()

        assertEquals(1, first.calls)
        assertEquals(0, second.calls)
    }

    private class FakeProvider(private val block: Boolean = false) : RemoteLlmConnector.Provider {
        override val label: String = "fake"
        var calls: Int = 0
        val started = CompletableDeferred<Unit>()

        override fun isConfigured(): Boolean = true

        override suspend fun complete(prompt: String, params: Map<String, String>): String {
            calls++
            started.complete(Unit)
            if (block) {
                kotlinx.coroutines.awaitCancellation()
            }
            return "ok"
        }
    }
}
