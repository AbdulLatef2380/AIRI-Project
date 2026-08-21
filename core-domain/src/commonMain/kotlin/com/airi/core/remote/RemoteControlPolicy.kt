package com.airi.core.remote

enum class RemoteControlCommandType {
    REQUEST_STATUS,
    START_NEW_DRAFT,
    SUBMIT_TEXT_REQUEST,
    CANCEL_OWNED_REQUEST
}

data class RemoteControlCommand(
    val commandId: String,
    val pairingId: String,
    val controllerDeviceId: String,
    val sequence: Long,
    val issuedAtMillis: Long,
    val type: RemoteControlCommandType,
    val text: String? = null
)

data class RemoteControlSession(
    val pairingId: String,
    val controllerDeviceId: String,
    val desktopDeviceId: String,
    val expiresAtMillis: Long,
    val lastAcceptedSequence: Long = 0,
    val allowedCommands: Set<RemoteControlCommandType>,
    val revoked: Boolean = false
)

sealed interface RemoteControlDecision {
    data class Accepted(val updatedSession: RemoteControlSession) : RemoteControlDecision
    data class Rejected(val reason: String) : RemoteControlDecision
}

object RemoteControlPolicy {
    const val MAX_TEXT_REQUEST_CHARS = 8_000

    fun decide(
        session: RemoteControlSession,
        command: RemoteControlCommand,
        nowMillis: Long
    ): RemoteControlDecision {
        if (session.revoked) return RemoteControlDecision.Rejected("The paired desktop session has been revoked.")
        if (nowMillis >= session.expiresAtMillis) return RemoteControlDecision.Rejected("The paired desktop session has expired.")
        if (command.pairingId != session.pairingId) return RemoteControlDecision.Rejected("The pairing identifier does not match this desktop session.")
        if (command.controllerDeviceId != session.controllerDeviceId) return RemoteControlDecision.Rejected("This controller is not paired with the desktop session.")
        if (command.sequence <= session.lastAcceptedSequence) return RemoteControlDecision.Rejected("This command was already processed or is out of order.")
        if (command.type !in session.allowedCommands) return RemoteControlDecision.Rejected("This command is not allowed by the paired desktop.")
        if (command.type == RemoteControlCommandType.SUBMIT_TEXT_REQUEST) {
            val text = command.text?.trim().orEmpty()
            if (text.isEmpty()) return RemoteControlDecision.Rejected("A remote text request cannot be empty.")
            if (text.length > MAX_TEXT_REQUEST_CHARS) return RemoteControlDecision.Rejected("A remote text request exceeds the allowed size.")
        } else if (command.text != null) {
            return RemoteControlDecision.Rejected("This command must not include text payload.")
        }

        return RemoteControlDecision.Accepted(session.copy(lastAcceptedSequence = command.sequence))
    }
}
