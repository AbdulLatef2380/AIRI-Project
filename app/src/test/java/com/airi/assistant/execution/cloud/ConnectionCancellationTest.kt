package com.airi.assistant.execution.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Report item 10: a timeout must break a read that is blocked on a silent server.
 * The server below accepts the request and never answers.
 */
class ConnectionCancellationTest {

    @Test
    fun timeoutInterruptsABlockedRead() {
        ServerSocket(0).use { server ->
            val held = mutableListOf<java.net.Socket>()
            val acceptor = thread(isDaemon = true) {
                try { while (true) held.add(server.accept()) } catch (_: Exception) {}
            }
            val started = System.currentTimeMillis()
            val result = runBlocking {
                withTimeoutOrNull(400) {
                    withContext(Dispatchers.IO) {
                        val conn = URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection
                        conn.connectTimeout = 2_000
                        conn.readTimeout = 60_000 // would block for a minute without the watcher
                        val watcher = watchConnectionForCancellation(conn)
                        try {
                            conn.responseCode // blocks: the server never responds
                        } catch (_: java.io.IOException) {
                            -1
                        } finally {
                            watcher.cancel()
                        }
                    }
                }
            }
            val elapsed = System.currentTimeMillis() - started
            held.forEach { runCatching { it.close() } }
            acceptor.interrupt()
            assertNull("the timeout must win over the blocked read", result)
            assertTrue("blocked read was not interrupted (elapsed=${elapsed}ms)", elapsed < 10_000)
        }
    }

    @Test
    fun watcherDoesNotKeepTheScopeAliveAfterNormalCompletion() {
        val result = runBlocking {
            withTimeoutOrNull(5_000) {
                withContext(Dispatchers.IO) {
                    ServerSocket(0).use { server ->
                        val conn = URL("http://127.0.0.1:${server.localPort}/").openConnection() as HttpURLConnection
                        val watcher = watchConnectionForCancellation(conn)
                        try { "done" } finally { watcher.cancel() }
                    }
                }
            }
        }
        assertTrue(result == "done")
    }
}
