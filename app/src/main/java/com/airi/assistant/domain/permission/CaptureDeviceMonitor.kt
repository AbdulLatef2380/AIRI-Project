package com.airi.assistant.domain.permission

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** Availability is observational only; AVAILABLE never grants permission or guarantees a later open. */
enum class CaptureAvailability { CHECKING, AVAILABLE, BUSY, UNSUPPORTED, UNKNOWN }

data class CaptureDeviceSnapshot(
    val cameraPermissionGranted: Boolean = false,
    val cameraAvailability: CaptureAvailability = CaptureAvailability.UNKNOWN,
    val microphonePermissionGranted: Boolean = false,
    val microphoneAvailability: CaptureAvailability = CaptureAvailability.UNKNOWN,
    /** True when Android exposes at least one active recording configuration to this app. */
    val microphoneRecordingObserved: Boolean? = null,
    val permissionRevision: Long = 0L,
)

/**
 * Observes hardware availability without opening either sensor. The owning UI must close the
 * monitor when it leaves composition. Runtime permissions are refreshed on resume by the caller.
 */
class CaptureDeviceMonitor(
    context: Context,
    private val onSnapshot: (CaptureDeviceSnapshot) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val permissionService = PermissionService(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val cameraIds = linkedSetOf<String>()
    private val cameraAvailability = mutableMapOf<String, Boolean>()
    private var cameraEnumerationSucceeded = false
    private var cameraCallbackRegistered = false
    private var audioCallbackRegistered = false
    private var audioDeviceCallbackRegistered = false
    private var recordingActive: Boolean? = null
    private var microphoneHardwarePresent: Boolean? = null
    private var permissionRevision = 0L
    private var started = false

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            cameraEnumerationSucceeded = true
            cameraIds += cameraId
            cameraAvailability[cameraId] = true
            publish()
        }

        override fun onCameraUnavailable(cameraId: String) {
            cameraEnumerationSucceeded = true
            cameraIds += cameraId
            cameraAvailability[cameraId] = false
            publish()
        }

        override fun onCameraRemoved(cameraId: String) {
            cameraIds -= cameraId
            cameraAvailability.remove(cameraId)
            publish()
        }
    }

    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>?) {
            recordingActive = configs?.isNotEmpty()
            publish()
        }
    }

    private val audioDeviceCallback = object : AudioManager.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out android.media.AudioDeviceInfo>?) {
            refreshMicrophoneHardware()
            publish()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>?) {
            refreshMicrophoneHardware()
            publish()
        }
    }

    fun start() {
        if (started) return
        started = true
        enumerateCameras()
        cameraManager?.let { manager ->
            runCatching { manager.registerAvailabilityCallback(cameraCallback, mainHandler) }
                .onSuccess { cameraCallbackRegistered = true }
        }
        audioManager?.let { manager ->
            runCatching { manager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler) }
                .onSuccess { audioDeviceCallbackRegistered = true }
        }
        refreshPermissions()
    }

    /** Refresh after activity resume or after an ActivityResult permission prompt. */
    fun refreshPermissions() {
        permissionRevision++
        refreshRecordingMonitor()
        publish()
    }

    private fun refreshRecordingMonitor() {
        refreshMicrophoneHardware()
        val manager = audioManager ?: run {
            recordingActive = null
            return
        }
        if (!permissionService.hasMicrophoneAccess()) {
            if (audioCallbackRegistered) runCatching { manager.unregisterAudioRecordingCallback(audioCallback) }
            audioCallbackRegistered = false
            recordingActive = null
            return
        }
        runCatching {
            recordingActive = manager.activeRecordingConfigurations.isNotEmpty()
            if (!audioCallbackRegistered) {
                manager.registerAudioRecordingCallback(audioCallback, mainHandler)
                audioCallbackRegistered = true
            }
        }.onFailure { recordingActive = null }
    }

    private fun refreshMicrophoneHardware() {
        microphoneHardwarePresent = runCatching {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ||
                audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.isNotEmpty() == true
        }.getOrNull()
    }

    private fun enumerateCameras() {
        val manager = cameraManager ?: return
        runCatching {
            cameraIds.clear()
            cameraIds.addAll(manager.cameraIdList)
            cameraEnumerationSucceeded = true
        }.onFailure {
            cameraEnumerationSucceeded = false
        }
    }

    private fun snapshot(): CaptureDeviceSnapshot {
        val cameraPermission = permissionService.hasCameraAccess()
        val microphonePermission = permissionService.hasMicrophoneAccess()
        val cameraFeature = runCatching {
            appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        }.getOrNull()
        val knownStates = cameraAvailability.filterKeys { it in cameraIds }
        val cameraState = CaptureDevicePolicy.camera(
            enumerationSucceeded = cameraEnumerationSucceeded,
            hardwareFeaturePresent = cameraFeature,
            cameraIds = cameraIds,
            availabilityById = knownStates,
        )
        val microphoneState = CaptureDevicePolicy.microphone(microphoneHardwarePresent)
        return CaptureDeviceSnapshot(
            cameraPermissionGranted = cameraPermission,
            cameraAvailability = cameraState,
            microphonePermissionGranted = microphonePermission,
            microphoneAvailability = microphoneState,
            microphoneRecordingObserved = recordingActive,
            permissionRevision = permissionRevision,
        )
    }

    private fun publish() {
        if (started) onSnapshot(snapshot())
    }

    override fun close() {
        if (!started) return
        started = false
        if (cameraCallbackRegistered) runCatching { cameraManager?.unregisterAvailabilityCallback(cameraCallback) }
        if (audioCallbackRegistered) runCatching { audioManager?.unregisterAudioRecordingCallback(audioCallback) }
        if (audioDeviceCallbackRegistered) runCatching { audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback) }
        cameraCallbackRegistered = false
        audioCallbackRegistered = false
        audioDeviceCallbackRegistered = false
    }
}

internal object CaptureDevicePolicy {
    fun camera(
        enumerationSucceeded: Boolean,
        hardwareFeaturePresent: Boolean?,
        cameraIds: Set<String>,
        availabilityById: Map<String, Boolean>,
    ): CaptureAvailability {
        if (!enumerationSucceeded) return CaptureAvailability.UNKNOWN
        if (cameraIds.isEmpty()) {
            return if (hardwareFeaturePresent == false) CaptureAvailability.UNSUPPORTED else CaptureAvailability.UNKNOWN
        }
        if (!availabilityById.keys.containsAll(cameraIds)) return CaptureAvailability.CHECKING
        return if (cameraIds.any { availabilityById[it] == true }) CaptureAvailability.AVAILABLE
        else CaptureAvailability.BUSY
    }

    fun microphone(hardwareFeaturePresent: Boolean?): CaptureAvailability = when (hardwareFeaturePresent) {
        true -> CaptureAvailability.AVAILABLE
        false -> CaptureAvailability.UNSUPPORTED
        null -> CaptureAvailability.UNKNOWN
    }
}
