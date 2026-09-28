package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPayloadContractTest {
    @Test
    fun nativeAttachmentPayloadHasMimeAndNonEmptyContent() {
        val request = ExecutionRequest(
            prompt = "analyze",
            inlineDataParts = listOf(
                ExecutionRequest.InlineDataPart(
                    mimeType = "application/pdf",
                    base64Data = "JVBERi0xLjQ=",
                    fileName = "report.pdf",
                    attachmentId = "pdf-1",
                )
            ),
        )
        assertTrue(GeminiPayloadContract.containsNonEmptyInlineContent(request))
        assertEquals("application/pdf", GeminiPayloadContract.descriptors(request).single().mimeType)
        assertTrue(GeminiPayloadContract.descriptors(request).single().encodedLength > 0)
    }

    @Test
    fun emptyPayloadCannotProduceContentEvidence() {
        val request = ExecutionRequest(
            prompt = "analyze",
            inlineDataParts = listOf(
                ExecutionRequest.InlineDataPart("", "", attachmentId = "empty")
            ),
        )
        assertFalse(GeminiPayloadContract.containsNonEmptyInlineContent(request))
    }
}
