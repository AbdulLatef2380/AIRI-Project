package com.airi.assistant.ai.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

class RemoteModelExecutorTest {

    @Test
    fun testConnectionAcceptsSuccessfulModelsEndpoint() = runBlocking {
        withServer(statusCode = 200) { baseUrl ->
            val result = RemoteModelExecutor().testConnection(
                RemoteModel(
                    id = "local-test",
                    name = "test-model",
                    serverUrl = baseUrl
                )
            )
            assertTrue(result)
        }
    }

    @Test
    fun testConnectionRejectsUnauthorizedModelsEndpoint() = runBlocking {
        withServer(statusCode = 401) { baseUrl ->
            val result = RemoteModelExecutor().testConnection(
                RemoteModel(
                    id = "local-test",
                    name = "test-model",
                    serverUrl = baseUrl,
                    apiKey = "invalid"
                )
            )
            assertFalse(result)
        }
    }

    private suspend fun withServer(statusCode: Int, block: suspend (String) -> Unit) {
        val server = ServerSocket(0)
        val responder = Thread {
            server.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                while (reader.readLine().isNotEmpty()) Unit
                val reason = if (statusCode in 200..299) "OK" else "Unauthorized"
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.write("HTTP/1.1 $statusCode $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                    writer.flush()
                }
            }
        }
        responder.start()
        try {
            block("http://127.0.0.1:${server.localPort}")
        } finally {
            server.close()
            responder.join(1_000)
        }
    }
}
