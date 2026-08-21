package com.airi.core.remote

data class RemoteAuthentication(
    val ownerId: String,
    val deviceId: String,
    val authenticatedAtMillis: Long,
    val expiresAtMillis: Long
)

data class RemoteAuthorizationRequest(
    val authentication: RemoteAuthentication,
    val session: RemoteControlSession,
    val command: RemoteControlCommand,
    val nowMillis: Long
)

sealed interface RemoteAuthorizationDecision {
    data object Allowed : RemoteAuthorizationDecision
    data class Rejected(val reason: String) : RemoteAuthorizationDecision
}

sealed interface RemoteTransportResult {
    data object Accepted : RemoteTransportResult
    data class Rejected(val reason: String) : RemoteTransportResult
}

interface RemoteControlTransport {
    fun send(ownerId: String, desktopDeviceId: String, command: RemoteControlCommand): RemoteTransportResult
    fun takePending(ownerId: String, desktopDeviceId: String): List<RemoteControlCommand>
}

interface RemoteControlAuthenticator {
    fun authenticate(nowMillis: Long): RemoteAuthentication?
}

interface RemoteControlAuthorizer {
    fun authorize(request: RemoteAuthorizationRequest): RemoteAuthorizationDecision
}

interface DeviceRegistry {
    fun find(ownerId: String, deviceId: String): RemoteDevice?
    fun upsert(device: RemoteDevice)
    fun revoke(ownerId: String, deviceId: String, nowMillis: Long): RemoteDevice?
}

interface DevicePairingService {
    fun confirm(
        request: RemotePairingRequest,
        desktop: RemoteDevice,
        controller: RemoteDevice,
        confirmation: RemotePairingConfirmation,
        sessionId: String,
        sessionExpiresAtMillis: Long,
        allowedCommands: Set<RemoteControlCommandType>
    ): RemotePairingDecision
}

interface CommandReplayGuard {
    fun accept(commandId: String, desktopDeviceId: String, expiresAtMillis: Long, nowMillis: Long): Boolean
}

object DefaultRemoteControlAuthorizer : RemoteControlAuthorizer {
    override fun authorize(request: RemoteAuthorizationRequest): RemoteAuthorizationDecision {
        val authentication = request.authentication
        if (request.nowMillis >= authentication.expiresAtMillis) {
            return RemoteAuthorizationDecision.Rejected("The controller authentication has expired.")
        }
        if (authentication.deviceId != request.command.controllerDeviceId) {
            return RemoteAuthorizationDecision.Rejected("The authenticated controller does not match the command.")
        }
        return when (val policy = RemoteControlPolicy.decide(request.session, request.command, request.nowMillis)) {
            is RemoteControlDecision.Accepted -> RemoteAuthorizationDecision.Allowed
            is RemoteControlDecision.Rejected -> RemoteAuthorizationDecision.Rejected(policy.reason)
        }
    }
}
