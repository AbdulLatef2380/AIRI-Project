package com.airi.assistant.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureDevicePolicyTest {
    @Test
    fun cameraRemainsCheckingUntilEachEnumeratedCameraReportsAvailability() {
        assertEquals(
            CaptureAvailability.CHECKING,
            CaptureDevicePolicy.camera(
                enumerationSucceeded = true,
                hardwareFeaturePresent = true,
                cameraIds = setOf("0", "1"),
                availabilityById = mapOf("0" to true),
            ),
        )
    }

    @Test
    fun cameraIsAvailableIfAnyCameraCanBeOpenedAndBusyOnlyWhenAllAreUnavailable() {
        assertEquals(
            CaptureAvailability.AVAILABLE,
            CaptureDevicePolicy.camera(true, true, setOf("0", "1"), mapOf("0" to false, "1" to true)),
        )
        assertEquals(
            CaptureAvailability.BUSY,
            CaptureDevicePolicy.camera(true, true, setOf("0", "1"), mapOf("0" to false, "1" to false)),
        )
    }

    @Test
    fun failedEnumerationIsUnknownAndExplicitlyAbsentHardwareIsUnsupported() {
        assertEquals(
            CaptureAvailability.UNKNOWN,
            CaptureDevicePolicy.camera(false, false, emptySet(), emptyMap()),
        )
        assertEquals(
            CaptureAvailability.UNSUPPORTED,
            CaptureDevicePolicy.camera(true, false, emptySet(), emptyMap()),
        )
        assertEquals(CaptureAvailability.UNKNOWN, CaptureDevicePolicy.microphone(null))
        assertEquals(CaptureAvailability.UNSUPPORTED, CaptureDevicePolicy.microphone(false))
        assertEquals(CaptureAvailability.AVAILABLE, CaptureDevicePolicy.microphone(true))
    }
}
