package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ui.generative.GenerativeUiStructuredOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderUiToolTest {
    @Test
    fun validDocumentIsReturnedAsCanonicalFence() {
        val result = RenderUiTool.render(
            """{"version":1,"blocks":[{"type":"stat","id":"s1","value":"42"}]}""",
            "Summary",
        )

        assertTrue(result is RenderUiTool.Result.Accepted)
        val accepted = result as RenderUiTool.Result.Accepted
        assertEquals(1, accepted.blockCount)
        assertTrue(accepted.content.startsWith("Summary\n\n```airi-ui"))
    }

    @Test
    fun invalidDocumentIsRejectedByTheSharedCompiler() {
        val result = RenderUiTool.render("""{"version":1,"blocks":[{"type":"unknown","id":"x"}]}""")

        assertTrue(result is RenderUiTool.Result.Rejected)
        assertTrue((result as RenderUiTool.Result.Rejected).reason.contains("unknown_block"))
    }

    @Test
    fun nestedFenceIsRejected() {
        val result = RenderUiTool.render("```airi-ui\n{}\n```")

        assertTrue(result is RenderUiTool.Result.Rejected)
    }

    @Test
    fun nativeProviderJsonIsNormalizedToTheSameFenceContract() {
        val native = """{"version":1,"blocks":[{"type":"stat","id":"s","value":"7"}]}"""
        val fence = GenerativeUiStructuredOutput.toFence(native)

        assertTrue(fence?.startsWith("```airi-ui\n") == true)
        assertTrue(fence?.endsWith("\n```") == true)
        assertNull(GenerativeUiStructuredOutput.toFence("not json"))
    }

    @Test
    fun structuredOutputIsRestrictedToNativeFirstPartyProviders() {
        assertTrue(GenerativeUiStructuredOutput.supportedProvider("openai"))
        assertTrue(GenerativeUiStructuredOutput.supportedProvider("gemini"))
        assertTrue(GenerativeUiStructuredOutput.supportedProvider("anthropic"))
        assertTrue(!GenerativeUiStructuredOutput.supportedProvider("openrouter"))
        assertTrue(!GenerativeUiStructuredOutput.supportedProvider("local"))
    }
}
