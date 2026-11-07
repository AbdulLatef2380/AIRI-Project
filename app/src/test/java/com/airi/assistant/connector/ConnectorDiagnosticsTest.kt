package com.airi.assistant.connector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorDiagnosticsTest {
    @Test
    fun classifierSeparatesAuthenticationAuthorizationRateLimitAndTransport() {
        assertEquals(ConnectorErrorClass.AUTHENTICATION, ConnectorErrorClassifier.classify("authorization_required"))
        assertEquals(ConnectorErrorClass.AUTHORIZATION, ConnectorErrorClassifier.classify("permission_denied"))
        assertEquals(ConnectorErrorClass.RATE_LIMIT, ConnectorErrorClassifier.classify("http_429"))
        assertEquals(ConnectorErrorClass.NETWORK, ConnectorErrorClassifier.classify("network_error"))
        assertEquals(ConnectorErrorClass.TIMEOUT, ConnectorErrorClassifier.classify("timeout"))
    }

    @Test
    fun storeRetainsTimingsAndFailureCodeButNeverPayload() {
        val store = ConnectorDiagnosticsStore(maxConnectors = 1)
        store.record("github", ConnectorOutput.Success("private response", durationMs = 42L), durationMs = 42L, nowMs = 100L)
        store.record("github", ConnectorOutput.Failure("http_429", "provider details", retryable = true), durationMs = 9L, nowMs = 200L)

        val snapshot = store.snapshots.value.getValue("github")
        assertEquals(9L, snapshot.lastRequestDurationMs)
        assertEquals(ConnectorErrorClass.RATE_LIMIT, snapshot.lastFailureClass)
        assertEquals("http_429", snapshot.lastFailureCode)
        assertTrue(snapshot.rateLimitObserved)
        assertEquals(100L, snapshot.lastSuccessfulRequestMs)
    }

    @Test
    fun storeIsBoundedByConnectorCount() {
        val store = ConnectorDiagnosticsStore(maxConnectors = 2)
        repeat(10) { id -> store.record("connector-$id", ConnectorOutput.Success("ignored"), 1L, id.toLong()) }
        assertEquals(2, store.snapshots.value.size)
        assertTrue(store.snapshots.value.keys.containsAll(listOf("connector-8", "connector-9")))
    }
}
