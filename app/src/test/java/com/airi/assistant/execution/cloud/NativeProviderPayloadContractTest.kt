package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.ExecutionRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeProviderPayloadContractTest {
    private fun request() = ExecutionRequest(
        prompt = "Inspect these files",
        imageParts = listOf(
            ExecutionRequest.ImagePart("image/png", "aW1hZ2U=", "image-1"),
        ),
        inlineDataParts = listOf(
            ExecutionRequest.InlineDataPart(
                mimeType = "application/pdf",
                base64Data = "JVBERi0xLjQ=",
                fileName = "report.pdf",
                attachmentId = "pdf-1",
            ),
        ),
    )

    @Test
    fun openAiResponsesContainsInputImageAndInputFile() {
        val body = OpenAIResponsesPayloadContract.buildRequestBody(request(), "gpt-4.1")
        assertTrue(OpenAIResponsesPayloadContract.requiresResponses(request()))
        assertTrue(OpenAIResponsesPayloadContract.containsNonEmptyContent(request()))
        assertTrue(body.contains("\"type\":\"input_image\""))
        assertTrue(body.contains("\"type\":\"input_file\""))
        assertTrue(body.contains("application/pdf"))
        assertTrue(body.contains("report.pdf"))
    }

    @Test
    fun anthropicUsesImageAndPdfDocumentBlocks() {
        val body = AnthropicPayloadContract.buildRequestBody(request(), "claude-sonnet-4-5")
        assertTrue(AnthropicPayloadContract.containsNonEmptyContent(request()))
        assertTrue(body.contains("\"type\":\"image\""))
        assertTrue(body.contains("\"type\":\"document\""))
        assertTrue(body.contains("\"media_type\":\"application/pdf\""))
    }

    @Test
    fun anthropicRejectsNonPdfNativeFileParts() {
        val office = request().copy(
            imageParts = emptyList(),
            inlineDataParts = listOf(
                ExecutionRequest.InlineDataPart(
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    base64Data = "ZG9j",
                    fileName = "brief.docx",
                    attachmentId = "docx-1",
                ),
            ),
        )
        assertFalse(AnthropicPayloadContract.containsNonEmptyContent(office))
    }
}
