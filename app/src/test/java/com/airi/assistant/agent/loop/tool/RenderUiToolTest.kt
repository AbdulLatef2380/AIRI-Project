package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
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
}
