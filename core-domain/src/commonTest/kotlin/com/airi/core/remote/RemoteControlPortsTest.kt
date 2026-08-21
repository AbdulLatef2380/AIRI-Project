package com.airi.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlPortsTest {

    private val session = RemoteControlSession(
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        desktopDeviceId = "desktop-1",
        expiresAtMillis = 10_000,
        allowedCommands = setOf(RemoteControlCommandType.REQUEST_STATUS)
    )

    private val command = RemoteControlCommand(
        commandId = "command-1",
        pairingId = "pair-1",
        controllerDeviceId = "android-1",
        sequence = 1,
        issuedAtMillis = 1_000,
        type = RemoteControlCommandType.REQUEST_STATUS
    )

    @Test
    fun `mock transport routes commands only to owner desktop path`() {
        val transport = InMemoryRemoteControlTransport()
        assertIs<RemoteTransportResult.Accepted>(transport.send("owner-1", "desktop-1", command))
        assertEquals(listOf(command), transport.takePending("owner-1", "desktop-1"))
        assertTrue(transport.takePending("owner-2", "desktop-1").isEmpty())
    }

    @Test
    fun `authorizer rejects expired authentication and mismatched controller`() {
        val expired = RemoteAuthentication("owner-1", "android-1", 100, 1_000)
        assertIs<RemoteAuthorizationDecision.Rejected>(
            DefaultRemoteControlAuthorizer.authorize(RemoteAuthorizationRequest(expired, session, command, 1_000))
        )
        val wrongController = RemoteAuthentication("owner-1", "android-2", 100, 10_000)
        assertIs<RemoteAuthorizationDecision.Rejected>(
            DefaultRemoteControlAuthorizer.authorize(RemoteAuthorizationRequest(wrongController, session, command, 2_000))
        )
    }

    @Test
    fun `replay guard and registry enforce single command and revocation`() {
        val replay = InMemoryReplayGuard()
        assertTrue(replay.accept("command-1", "desktop-1", 10_000, 1_000))
        assertTrue(!replay.accept("command-1", "desktop-1", 10_000, 1_001))
        assertTrue(!replay.accept("command-2", "desktop-1", 1_000, 1_000))

        val registry = InMemoryDeviceRegistry()
        registry.upsert(
            RemoteDevice("desktop-1", "owner-1", "Desktop", RemoteDevicePlatform.DESKTOP, 100, 100, RemoteDeviceStatus.PAIRED, emptySet())
        )
        assertEquals(RemoteDeviceStatus.REVOKED, registry.revoke("owner-1", "desktop-1", 2_000)?.status)
        assertNull(registry.find("owner-2", "desktop-1"))
    }
}
