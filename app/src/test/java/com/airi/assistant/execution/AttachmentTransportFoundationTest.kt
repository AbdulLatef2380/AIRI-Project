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
    fun plainTextAttachmentCanUseExtractionWithoutProviderTransport() {
        val resolution = AttachmentTransportResolver.resolve(
            attachmentId = "message-txt",
            contentType = "text/plain",
            provider = CloudProvider.GEMINI,
            nativeInlineReady = false,
            extractedTextAvailable = true,
        )
        assertEquals(AttachmentTransport.EXTRACTED_TEXT, resolution.transport)
    }

    @Test
    fun localVisionIsAnExplicitTransportRatherThanAnUnsupportedCloudFile() {
        val resolution = AttachmentTransportResolver.resolve(
            attachmentId = "local-image",
            contentType = "image/jpeg",
            provider = null,
            nativeInlineReady = false,
            extractedTextAvailable = false,
            localVisionReady = true,
        )
        assertEquals(AttachmentTransport.LOCAL_VISION, resolution.transport)
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

    @Test
    fun nativeSuccessCannotBeClaimedWithoutDispatchAndProviderResponseEvidence() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(AttachmentResolution("image-2", AttachmentTransport.NATIVE_INLINE)),
            provider = CloudProvider.OPENAI,
        )
        trace.markPayloadContainsContent("image-2", 64L)
        trace.markProviderResponse(true, listOf("image-2"))

        assertEquals(AttachmentDeliveryStatus.REJECTED_WITH_REASON, trace.snapshot().single().status)
        assertFalse(trace.isTransportSuccessful())
    }

    @Test
    fun extractedTextIsNotSentUntilTheExecutionCompletes() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(AttachmentResolution("text-1", AttachmentTransport.EXTRACTED_TEXT)),
            provider = CloudProvider.GEMINI,
        )
        trace.markExtractedText("text-1", 12)
        trace.markExecutionRequestBuilt()

        assertEquals(AttachmentDeliveryStatus.PREPARED_EXTRACTED_TEXT, trace.snapshot().single().status)
        assertFalse(trace.isTransportSuccessful())

        trace.markExecutionCompleted()
        assertEquals(AttachmentDeliveryStatus.SENT_EXTRACTED_TEXT, trace.snapshot().single().status)
        assertTrue(trace.isTransportSuccessful())
    }

    @Test
    fun extractedTextTraceCapturesTheActualCloudProvider() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(AttachmentResolution("text-2", AttachmentTransport.EXTRACTED_TEXT)),
            provider = null,
        )
        trace.setProvider(CloudProvider.GEMINI)
        assertEquals(CloudProvider.GEMINI, trace.snapshot().single().provider)
    }

    @Test
    fun localVisionRequiresIncludedBytesAndCompletedInference() {
        val trace = AttachmentDeliveryTrace(
            resolutions = listOf(AttachmentResolution("local-image", AttachmentTransport.LOCAL_VISION)),
            provider = null,
        )
        trace.markLocalVisionContent("local-image", 256L)
        trace.markExecutionRequestBuilt()
        assertFalse(trace.isTransportSuccessful())

        trace.markExecutionCompleted()
        assertEquals(AttachmentDeliveryStatus.SENT_LOCAL_VISION, trace.snapshot().single().status)
        assertTrue(trace.isTransportSuccessful())
    }
}
