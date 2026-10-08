package com.airi.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class VoiceCaptureArbiterTest {
    @Test fun rejectsParallelOwnerAndAllowsRelease() {
        val first = "test:first:${UUID.randomUUID()}"
        val second = "test:second:${UUID.randomUUID()}"
        try {
            assertTrue(VoiceCaptureArbiter.acquire(first))
            assertFalse(VoiceCaptureArbiter.acquire(second))
            assertEquals(first, VoiceCaptureArbiter.currentOwner)
            assertTrue(VoiceCaptureArbiter.release(first))
            assertTrue(VoiceCaptureArbiter.acquire(second))
        } finally {
            VoiceCaptureArbiter.release(first)
            VoiceCaptureArbiter.release(second)
        }
    }

    @Test fun preemptionRequiresOldOwnerToReleaseSynchronously() {
        val oldOwner = "test:hotword:${UUID.randomUUID()}"
        val newOwner = "test:live:${UUID.randomUUID()}"
        VoiceCaptureArbiter.registerPreemptionHandler(oldOwner) {
            VoiceCaptureArbiter.release(oldOwner)
        }
        try {
            assertTrue(VoiceCaptureArbiter.acquire(oldOwner))
            assertTrue(VoiceCaptureArbiter.acquire(newOwner, preemptExisting = true))
            assertEquals(newOwner, VoiceCaptureArbiter.currentOwner)
            assertFalse(VoiceCaptureArbiter.release(oldOwner))
        } finally {
            VoiceCaptureArbiter.unregisterPreemptionHandler(oldOwner)
            VoiceCaptureArbiter.release(oldOwner)
            VoiceCaptureArbiter.release(newOwner)
        }
    }
}
