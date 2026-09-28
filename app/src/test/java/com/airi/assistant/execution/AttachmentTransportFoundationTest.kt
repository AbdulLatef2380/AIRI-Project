package com.airi.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentTransportFoundationTest {
    @Test
    fun resolverDistinguishesNotReadyFromUnsupported() {
        val notReady = AttachmentTransportResolver.resolve(
            attachmentId = "a",
            contentType = "application/pdf",
            provider = null,
            nativeInlineReady = false,
            extractedTextAvailable = false,
        )
        val unsupported = AttachmentTransportResolver.resolve(
            attachmentId = "b",
            contentType = "video/mp4",
            provider = CloudProvider.OPENAI,
            nativeInlineReady = false,
            extractedTextAvailable = false,
        )
        val notImplemented = AttachmentTransportResolver.resolve(
            attachmentId = "c",
            contentType = "application/pdf",
            provider = CloudProvider.OPENAI,
            nativeInlineReady = false,
            extractedTextAvailable = false,
            capabilitySupported = true,
        )
        assertEquals(AttachmentTransport.NOT_READY, notReady.transport)
        assertEquals(AttachmentTransport.UNSUPPORTED, unsupported.transport)
        assertEquals(AttachmentTransport.NOT_READY, notImplemented.transport)
    }

    @Test
    fun resolverPrefersNativeOverExtractionAndNeverExecutesWork() {
        val resolution = AttachmentTransportResolver.resolve(
            attachmentId = "pdf-1",
            contentType = "application/pdf",
            provider = CloudProvider.GEMINI,
            nativeInlineReady = true,
            extractedTextAvailable = true,
        )
        assertEquals(AttachmentTransport.NATIVE_INLINE, resolution.transport)
    }

    @Test
    fun deliveryCannotBecomeSentNativeBeforeProviderResponse() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(
                AttachmentResolution("image-1", AttachmentTransport.NATIVE_INLINE)
            ),
            provider = CloudProvider.GEMINI,
        )
        trace.mark(AttachmentDeliveryStage.LOCAL_ATTACHMENT_RESOLVED)
        trace.mark(AttachmentDeliveryStage.PROVIDER_PAYLOAD_BUILT, listOf("image-1"))
        trace.markPayloadContainsContent("image-1", 128L)

        val beforeResponse = trace.snapshot().single()
        assertTrue(beforeResponse.payloadIncluded)
        assertFalse(beforeResponse.status == AttachmentDeliveryStatus.SENT_NATIVE)
        assertTrue(AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED !in beforeResponse.stages)

        trace.mark(AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED, listOf("image-1"))
        trace.mark(AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED, listOf("image-1"))
        trace.markProviderResponse(true, listOf("image-1"))
        val afterResponse = trace.snapshot().single()
        assertEquals(AttachmentDeliveryStatus.SENT_NATIVE, afterResponse.status)
        assertTrue(AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED in afterResponse.stages)
    }

    @Test
    fun failedProviderResponseRejectsEvenWhenPayloadWasBuilt() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(AttachmentResolution("pdf-1", AttachmentTransport.NATIVE_INLINE)),
            provider = CloudProvider.GEMINI,
        )
        trace.markPayloadContainsContent("pdf-1", 64L)
        trace.markProviderResponse(false, listOf("pdf-1"))
        val evidence = trace.snapshot().single()
        assertEquals(AttachmentDeliveryStatus.REJECTED_WITH_REASON, evidence.status)
        assertTrue(evidence.payloadIncluded)
        assertTrue(evidence.failureReason.isNotBlank())
    }
}
