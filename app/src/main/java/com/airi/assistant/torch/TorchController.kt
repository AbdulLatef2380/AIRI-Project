package com.airi.assistant.torch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

sealed interface TorchResult {
    data class Success(val enabled: Boolean) : TorchResult
    data object PermissionRequired : TorchResult
    data object NotReady : TorchResult
    data object UnknownOutcome : TorchResult
    data class Failed(val reason: String) : TorchResult
}

/** Controls a real rear-camera torch and waits for Android's state callback. */
class TorchController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    suspend fun setEnabled(enabled: Boolean): TorchResult {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return TorchResult.PermissionRequired
        }
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: return TorchResult.NotReady
        } catch (error: Exception) {
            return TorchResult.Failed(error.javaClass.simpleName)
        }

        return try {
            withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val finished = AtomicBoolean(false)
                    val callback = object : CameraManager.TorchCallback() {
                        override fun onTorchModeChanged(id: String, isEnabled: Boolean) {
                            if (id == cameraId && isEnabled == enabled && finished.compareAndSet(false, true)) {
                                runCatching { cameraManager.unregisterTorchCallback(this) }
                                if (continuation.isActive) continuation.resume(TorchResult.Success(enabled))
                            }
                        }

                        override fun onTorchModeUnavailable(id: String) {
                            if (id == cameraId && finished.compareAndSet(false, true)) {
                                runCatching { cameraManager.unregisterTorchCallback(this) }
                                if (continuation.isActive) continuation.resume(TorchResult.NotReady)
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        if (finished.compareAndSet(false, true)) runCatching { cameraManager.unregisterTorchCallback(callback) }
                    }
                    try {
                        cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
                        cameraManager.setTorchMode(cameraId, enabled)
                    } catch (error: SecurityException) {
                        if (finished.compareAndSet(false, true)) runCatching { cameraManager.unregisterTorchCallback(callback) }
                        if (continuation.isActive) continuation.resume(TorchResult.PermissionRequired)
                    } catch (error: Exception) {
                        if (finished.compareAndSet(false, true)) runCatching { cameraManager.unregisterTorchCallback(callback) }
                        if (continuation.isActive) continuation.resume(TorchResult.Failed(error.javaClass.simpleName))
                    }
                }
            } ?: TorchResult.UnknownOutcome
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            TorchResult.Failed(error.javaClass.simpleName)
        }
    }

    private companion object { const val CALLBACK_TIMEOUT_MS = 1_500L }
}
