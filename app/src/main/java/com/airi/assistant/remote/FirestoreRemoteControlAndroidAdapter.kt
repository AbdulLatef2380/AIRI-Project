package com.airi.assistant.remote

import com.airi.assistant.domain.auth.AuthService
import com.airi.core.remote.RemoteControlCommandType
import com.airi.core.remote.RemoteControlPolicy
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

class FirestoreRemoteControlAndroidAdapter(
    private val authService: AuthService,
    private val firestore: FirebaseFirestore? = runCatching { FirebaseFirestore.getInstance() }.getOrNull(),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    sealed interface RegistrationResult {
        data object Registered : RegistrationResult
        data class Rejected(val reason: String) : RegistrationResult
    }

    sealed interface CommandResult {
        data object Enqueued : CommandResult
        data class Rejected(val reason: String) : CommandResult
    }

    suspend fun registerPendingController(
        deviceId: String,
        displayName: String,
        capabilities: Set<RemoteControlCommandType>
    ): RegistrationResult {
        val ownerId = authService.currentUserId
            ?: return RegistrationResult.Rejected("Sign in is required before registering a paired controller.")
        val database = firestore
            ?: return RegistrationResult.Rejected("Remote control is unavailable because Firestore is not configured.")
        if (!isValidDeviceId(deviceId)) {
            return RegistrationResult.Rejected("The controller device identifier is invalid.")
        }
        val normalizedName = displayName.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_DEVICE_NAME_CHARS) {
            return RegistrationResult.Rejected("The controller display name must contain 1 to $MAX_DEVICE_NAME_CHARS characters.")
        }
        if (capabilities.any { it !in CONTROLLER_CAPABILITIES }) {
            return RegistrationResult.Rejected("The controller requested an unsupported remote-control capability.")
        }

        val device = mapOf(
            "deviceId" to deviceId,
            "ownerId" to ownerId,
            "displayName" to normalizedName,
            "platform" to "ANDROID",
            "createdAt" to FieldValue.serverTimestamp(),
            "lastSeenAt" to FieldValue.serverTimestamp(),
            "status" to "PENDING",
            "capabilities" to capabilities.map(RemoteControlCommandType::name).sorted()
        )
        return try {
            database.collection("users").document(ownerId)
                .collection("devices").document(deviceId)
                .set(device)
                .await()
            RegistrationResult.Registered
        } catch (error: Exception) {
            RegistrationResult.Rejected("The controller registration was rejected by the remote-control service.")
        }
    }

    suspend fun enqueueCommand(
        desktopDeviceId: String,
        controllerDeviceId: String,
        sessionId: String,
        commandId: String,
        sequence: Long,
        type: RemoteControlCommandType,
        text: String? = null,
        correlationId: String
    ): CommandResult {
        val ownerId = authService.currentUserId
            ?: return CommandResult.Rejected("Sign in is required before sending a remote command.")
        val database = firestore
            ?: return CommandResult.Rejected("Remote control is unavailable because Firestore is not configured.")
        if (!isValidDeviceId(desktopDeviceId) || !isValidDeviceId(controllerDeviceId)) {
            return CommandResult.Rejected("The remote device identifier is invalid.")
        }
        if (!isValidIdentifier(sessionId) || !isValidIdentifier(commandId) || !isValidIdentifier(correlationId)) {
            return CommandResult.Rejected("The remote command identifier is invalid.")
        }
        if (sequence <= 0L) return CommandResult.Rejected("The remote command sequence must be positive.")

        val normalizedText = text?.trim()
        if (type == RemoteControlCommandType.SUBMIT_TEXT_REQUEST) {
            if (normalizedText.isNullOrEmpty()) return CommandResult.Rejected("A remote text request cannot be empty.")
            if (normalizedText.length > RemoteControlPolicy.MAX_TEXT_REQUEST_CHARS) {
                return CommandResult.Rejected("A remote text request exceeds the allowed size.")
            }
        } else if (text != null) {
            return CommandResult.Rejected("This remote command must not include a text payload.")
        }

        val payload: Map<String, String> = if (type == RemoteControlCommandType.SUBMIT_TEXT_REQUEST) {
            mapOf("text" to normalizedText.orEmpty())
        } else {
            emptyMap()
        }
        val command = mapOf(
            "commandId" to commandId,
            "ownerId" to ownerId,
            "desktopDeviceId" to desktopDeviceId,
            "controllerDeviceId" to controllerDeviceId,
            "sessionId" to sessionId,
            "commandType" to type.name,
            "payload" to payload,
            "sequence" to sequence,
            "createdAt" to FieldValue.serverTimestamp(),
            "expiresAt" to Timestamp(Date(nowMillis() + COMMAND_TTL_MILLIS)),
            "correlationId" to correlationId
        )
        return try {
            database.collection("users").document(ownerId)
                .collection("devices").document(desktopDeviceId)
                .collection("commands").document(commandId)
                .set(command)
                .await()
            CommandResult.Enqueued
        } catch (error: Exception) {
            CommandResult.Rejected("The remote command was rejected by the remote-control service.")
        }
    }

    private fun isValidDeviceId(value: String): Boolean = DEVICE_ID_PATTERN.matches(value)

    private fun isValidIdentifier(value: String): Boolean =
        value.length in 16..128 && IDENTIFIER_PATTERN.matches(value)

    private companion object {
        const val MAX_DEVICE_NAME_CHARS = 80
        const val COMMAND_TTL_MILLIS = 5 * 60 * 1_000L
        val DEVICE_ID_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
        val IDENTIFIER_PATTERN = Regex("[A-Za-z0-9_-]+")
        val CONTROLLER_CAPABILITIES = RemoteControlCommandType.entries.toSet()
    }
}
