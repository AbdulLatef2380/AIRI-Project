package com.airi.core.remote

enum class RemoteDevicePlatform {
    ANDROID,
    DESKTOP
}

enum class RemoteDeviceStatus {
    PENDING,
    PAIRED,
    REVOKED,
    OFFLINE
}

data class RemoteDevice(
    val deviceId: String,
    val ownerId: String,
    val displayName: String,
    val platform: RemoteDevicePlatform,
    val createdAtMillis: Long,
    val lastSeenAtMillis: Long,
    val status: RemoteDeviceStatus,
    val capabilities: Set<RemoteControlCommandType>
)

data class RemotePairingRequest(
    val requestId: String,
    val desktopDeviceId: String,
    val ownerId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val remainingAttempts: Int,
    val desktopApproved: Boolean = false,
    val revoked: Boolean = false
)

data class RemotePairingConfirmation(
    val requestId: String,
    val controllerDeviceId: String,
    val authenticatedOwnerId: String,
    val nowMillis: Long
)

sealed interface RemotePairingDecision {
    data class Paired(
        val session: RemoteControlSession,
        val controller: RemoteDevice,
        val desktop: RemoteDevice
    ) : RemotePairingDecision

    data class Rejected(val reason: String) : RemotePairingDecision
}

object DevicePairingPolicy {
    const val MAX_PAIRING_WINDOW_MILLIS = 5 * 60 * 1_000L
    const val MAX_PAIRING_ATTEMPTS = 5

    fun confirm(
        request: RemotePairingRequest,
        desktop: RemoteDevice,
        controller: RemoteDevice,
        confirmation: RemotePairingConfirmation,
        sessionId: String,
        sessionExpiresAtMillis: Long,
        allowedCommands: Set<RemoteControlCommandType>
    ): RemotePairingDecision {
        if (request.revoked) return RemotePairingDecision.Rejected("The pairing request has been revoked.")
        if (!request.desktopApproved) return RemotePairingDecision.Rejected("The desktop has not approved this pairing request.")
        if (confirmation.nowMillis >= request.expiresAtMillis) return RemotePairingDecision.Rejected("The pairing request has expired.")
        if (request.expiresAtMillis - request.createdAtMillis > MAX_PAIRING_WINDOW_MILLIS) {
            return RemotePairingDecision.Rejected("The pairing window exceeds the maximum duration.")
        }
        if (request.remainingAttempts !in 1..MAX_PAIRING_ATTEMPTS) {
            return RemotePairingDecision.Rejected("The pairing request has no remaining attempts.")
        }
        if (confirmation.requestId != request.requestId) return RemotePairingDecision.Rejected("The pairing request does not match.")
        if (desktop.deviceId != request.desktopDeviceId || desktop.platform != RemoteDevicePlatform.DESKTOP) {
            return RemotePairingDecision.Rejected("The requested desktop identity is invalid.")
        }
        if (controller.platform != RemoteDevicePlatform.ANDROID) return RemotePairingDecision.Rejected("Only an Android controller can confirm this request.")
        if (desktop.ownerId != request.ownerId || controller.ownerId != request.ownerId) {
            return RemotePairingDecision.Rejected("Devices must belong to the pairing owner.")
        }
        if (confirmation.authenticatedOwnerId != request.ownerId) {
            return RemotePairingDecision.Rejected("The authenticated owner does not match the pairing request.")
        }
        if (desktop.status == RemoteDeviceStatus.REVOKED || controller.status == RemoteDeviceStatus.REVOKED) {
            return RemotePairingDecision.Rejected("A revoked device cannot be paired.")
        }
        if (sessionExpiresAtMillis <= confirmation.nowMillis) return RemotePairingDecision.Rejected("The paired session must expire in the future.")

        return RemotePairingDecision.Paired(
            session = RemoteControlSession(
                pairingId = sessionId,
                controllerDeviceId = controller.deviceId,
                desktopDeviceId = desktop.deviceId,
                expiresAtMillis = sessionExpiresAtMillis,
                allowedCommands = allowedCommands
            ),
            controller = controller.copy(status = RemoteDeviceStatus.PAIRED, lastSeenAtMillis = confirmation.nowMillis),
            desktop = desktop.copy(status = RemoteDeviceStatus.PAIRED, lastSeenAtMillis = confirmation.nowMillis)
        )
    }

    fun revoke(device: RemoteDevice, nowMillis: Long): RemoteDevice = device.copy(
        status = RemoteDeviceStatus.REVOKED,
        lastSeenAtMillis = nowMillis
    )
}
