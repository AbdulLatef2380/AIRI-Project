package com.airi.core.remote

class InMemoryRemoteControlTransport : RemoteControlTransport {
    private val pending = mutableMapOf<String, MutableList<RemoteControlCommand>>()

    override fun send(ownerId: String, desktopDeviceId: String, command: RemoteControlCommand): RemoteTransportResult {
        if (ownerId.isBlank() || desktopDeviceId.isBlank()) return RemoteTransportResult.Rejected("Missing route identity.")
        pending.getOrPut("$ownerId/$desktopDeviceId") { mutableListOf() }.add(command)
        return RemoteTransportResult.Accepted
    }

    override fun takePending(ownerId: String, desktopDeviceId: String): List<RemoteControlCommand> =
        pending.remove("$ownerId/$desktopDeviceId").orEmpty()
}

class InMemoryDeviceRegistry : DeviceRegistry {
    private val devices = mutableMapOf<String, RemoteDevice>()

    override fun find(ownerId: String, deviceId: String): RemoteDevice? = devices["$ownerId/$deviceId"]

    override fun upsert(device: RemoteDevice) {
        devices["${device.ownerId}/${device.deviceId}"] = device
    }

    override fun revoke(ownerId: String, deviceId: String, nowMillis: Long): RemoteDevice? {
        val current = find(ownerId, deviceId) ?: return null
        return DevicePairingPolicy.revoke(current, nowMillis).also(::upsert)
    }
}

class InMemoryReplayGuard : CommandReplayGuard {
    private val accepted = mutableMapOf<String, Long>()

    override fun accept(commandId: String, desktopDeviceId: String, expiresAtMillis: Long, nowMillis: Long): Boolean {
        accepted.entries.removeAll { (_, expiry) -> expiry <= nowMillis }
        if (nowMillis >= expiresAtMillis || commandId.isBlank() || desktopDeviceId.isBlank()) return false
        return accepted.putIfAbsent("$desktopDeviceId/$commandId", expiresAtMillis) == null
    }
}

object DefaultDevicePairingService : DevicePairingService {
    override fun confirm(
        request: RemotePairingRequest,
        desktop: RemoteDevice,
        controller: RemoteDevice,
        confirmation: RemotePairingConfirmation,
        sessionId: String,
        sessionExpiresAtMillis: Long,
        allowedCommands: Set<RemoteControlCommandType>
    ): RemotePairingDecision = DevicePairingPolicy.confirm(
        request, desktop, controller, confirmation, sessionId, sessionExpiresAtMillis, allowedCommands
    )
}
