package com.airi.assistant.ai.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RemoteModelExecutorConnectivityTest {
    @Test
    fun unreachableEndpointFailsAndReconnectSucceedsAfterServerReturns() = runBlocking {
        val unavailablePort = ServerSocket(0).use { it.localPort }
        val model = RemoteModel(
            id = "ollama-local",
            name = "test-model",
            serverUrl = "http://127.0.0.1:$unavailablePort/v1",
        )
        val executor = RemoteModelExecutor()

        assertFalse(executor.testConnection(model, timeoutMs = 500))

        ServerSocket(unavailablePort).use { server ->
            val responder = respondToModelsRequest(server)
            assertTrue(executor.testConnection(model, timeoutMs = 1_000))
            responder.join(1_000)
            assertFalse("Test server should have completed its response", responder.isAlive)
        }
    }

    @Test
    fun endpointThatAcceptsButDoesNotRespondTimesOut() = runBlocking {
        ServerSocket(0).use { server ->
            val stalledPeer = thread(name = "airi-stalled-endpoint-test", isDaemon = true) {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        // Consume the request headers, then intentionally withhold a response.
                        val reader = socket.getInputStream().bufferedReader()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        Thread.sleep(500)
                    }
                }
            }
            val model = RemoteModel(
                id = "lm-studio-local",
                name = "test-model",
                serverUrl = "http://127.0.0.1:${server.localPort}/v1",
            )

            assertFalse(RemoteModelExecutor().testConnection(model, timeoutMs = 100))
            stalledPeer.join(1_000)
        }
    }

    private fun respondToModelsRequest(server: ServerSocket): Thread = thread(
        name = "airi-local-endpoint-test-server",
        isDaemon = true,
    ) {
        runCatching {
            server.accept().use { socket: Socket ->
                socket.soTimeout = 1_000
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine()?.isNotEmpty() == true) Unit
                val body = "{\"data\":[]}"
                val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"
                socket.getOutputStream().use { output -> output.write(response.toByteArray(Charsets.UTF_8)) }
            }
        }
    }
}
