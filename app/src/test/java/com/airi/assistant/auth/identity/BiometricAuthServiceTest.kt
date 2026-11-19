package com.airi.assistant.auth.identity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiometricAuthServiceTest {
    @Test
    fun backgroundThenForegroundInvalidatesOldSessionAndRequiresReauth() {
        val service = BiometricAuthService()
        val first = service.begin(available = true)
        assertNotNull(first)

        service.onBackground()
        service.onForeground()

        assertEquals(
            BiometricAuthService.Result.STALE,
            service.complete(first!!, BiometricAuthService.Outcome.SUCCESS)
        )
        val second = service.begin(available = true)
        assertNotNull(second)
        assertEquals(
            BiometricAuthService.Result.SUCCESS,
            service.complete(second!!, BiometricAuthService.Outcome.SUCCESS)
        )
    }

    @Test
    fun rotationInvalidatesCallbackFromPreviousActivity() {
        val service = BiometricAuthService()
        val session = service.begin(available = true)!!

        service.onRotation()

        assertEquals(
            BiometricAuthService.Result.STALE,
            service.complete(session, BiometricAuthService.Outcome.SUCCESS)
        )
        assertFalse(service.hasActiveSession())
    }

    @Test
    fun processRecreationInvalidatesOldSession() {
        val service = BiometricAuthService()
        val session = service.begin(available = true)!!

        service.onProcessRecreated()

        assertEquals(
            BiometricAuthService.Result.STALE,
            service.complete(session, BiometricAuthService.Outcome.SUCCESS)
        )
        val recreatedSession = service.begin(available = true)
        assertTrue(recreatedSession != null)
        assertEquals(
            BiometricAuthService.Result.SUCCESS,
            service.complete(recreatedSession!!, BiometricAuthService.Outcome.SUCCESS)
        )
    }

    @Test
    fun unavailableDoesNotCreateCapabilitySession() {
        val service = BiometricAuthService()

        assertEquals(null, service.begin(available = false))
        assertFalse(service.hasActiveSession())
    }

    @Test
    fun backgroundRejectsNewPromptUntilForeground() {
        val service = BiometricAuthService()
        service.onBackground()

        assertEquals(null, service.begin(available = true))
        service.onForeground()
        assertNotNull(service.begin(available = true))
    }

    @Test
    fun cancellationConsumesOnlyTheCurrentSession() {
        val service = BiometricAuthService()
        val session = service.begin(available = true)!!

        assertEquals(
            BiometricAuthService.Result.CANCELLED,
            service.complete(session, BiometricAuthService.Outcome.CANCELLED)
        )
        assertFalse(service.hasActiveSession())
        assertEquals(
            BiometricAuthService.Result.STALE,
            service.complete(session, BiometricAuthService.Outcome.SUCCESS)
        )
    }

    @Test
    fun failureDoesNotAuthorizeAndClearsSession() {
        val service = BiometricAuthService()
        val session = service.begin(available = true)!!

        assertEquals(
            BiometricAuthService.Result.FAILURE,
            service.complete(session, BiometricAuthService.Outcome.FAILURE)
        )
        assertFalse(service.hasActiveSession())
    }
}
