package com.airi.core.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DevicePairingPolicyTest {

    private fun desktop(status: RemoteDeviceStatus = RemoteDeviceStatus.PENDING) = RemoteDevice(
        deviceId = "desktop-1",
        ownerId = "owner-1",
        displayName = "AIRI Desktop",
        platform = RemoteDevicePlatform.DESKTOP,
        createdAtMillis = 100,
        lastSeenAtMillis = 100,
        status = status,
        capabilities = setOf(RemoteControlCommandType.REQUEST_STATUS)
    )

    private fun controller(ownerId: String = "owner-1", status: RemoteDeviceStatus = RemoteDeviceStatus.PENDING) = RemoteDevice(
        deviceId = "android-1",
        ownerId = ownerId,
        displayName = "AIRI Android",
        platform = RemoteDevicePlatform.ANDROID,
        createdAtMillis = 100,
        lastSeenAtMillis = 100,
        status = status,
        capabilities = setOf(RemoteControlCommandType.REQUEST_STATUS)
    )

    private fun request(
        expiresAtMillis: Long = 1_000,
        remainingAttempts: Int = 3,
        desktopApproved: Boolean = true,
        revoked: Boolean = false
    ) = RemotePairingRequest(
        requestId = "request-1",
        desktopDeviceId = "desktop-1",
        ownerId = "owner-1",
        createdAtMillis = 100,
        expiresAtMillis = expiresAtMillis,
        remainingAttempts = remainingAttempts,
        desktopApproved = desktopApproved,
        revoked = revoked
    )

    private fun confirmation(ownerId: String = "owner-1", nowMillis: Long = 200) = RemotePairingConfirmation(
        requestId = "request-1",
        controllerDeviceId = "android-1",
        authenticatedOwnerId = ownerId,
        nowMillis = nowMillis
    )

    @Test
    fun `pairs locally approved devices owned by authenticated user`() {
        val decision = DevicePairingPolicy.confirm(
            request(), desktop(), controller(), confirmation(), "session-1", 10_000,
            setOf(RemoteControlCommandType.REQUEST_STATUS)
        )

        val paired = assertIs<RemotePairingDecision.Paired>(decision)
        assertEquals(RemoteDeviceStatus.PAIRED, paired.controller.status)
        assertEquals("desktop-1", paired.session.desktopDeviceId)
    }

    @Test
    fun `rejects unapproved expired exhausted and revoked requests`() {
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(desktopApproved = false), desktop(), controller(), confirmation(), "s", 10_000, emptySet())
        )
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(expiresAtMillis = 200), desktop(), controller(), confirmation(nowMillis = 200), "s", 10_000, emptySet())
        )
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(remainingAttempts = 0), desktop(), controller(), confirmation(), "s", 10_000, emptySet())
        )
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(revoked = true), desktop(), controller(), confirmation(), "s", 10_000, emptySet())
        )
    }

    @Test
    fun `rejects mismatched owner and revoked device`() {
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(), desktop(), controller(ownerId = "owner-2"), confirmation(), "s", 10_000, emptySet())
        )
        assertIs<RemotePairingDecision.Rejected>(
            DevicePairingPolicy.confirm(request(), desktop(RemoteDeviceStatus.REVOKED), controller(), confirmation(), "s", 10_000, emptySet())
        )
        assertEquals(RemoteDeviceStatus.REVOKED, DevicePairingPolicy.revoke(controller(), 300).status)
    }
}
