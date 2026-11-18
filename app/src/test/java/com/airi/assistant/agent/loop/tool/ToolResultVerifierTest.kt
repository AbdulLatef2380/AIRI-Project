package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultVerifierTest {
    @Test
    fun fileDiffWithoutReceiptIdentityIsNotVerified() {
        val result = ToolDispatcher.ToolResult.Success(
            output = "saved",
            evidence = ToolDispatcher.ToolResult.Evidence(
                kind = ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF,
            ),
        )
        assertFalse(ToolResultVerifier.isVerified(result))
    }

    @Test
    fun artifactEvidenceWithIdentityIsVerifiedOnlyWithOutput() {
        val evidence = ToolDispatcher.ToolResult.Evidence(
            kind = ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF,
            artifactId = "artifact-1",
        )
        assertTrue(ToolResultVerifier.isVerified(ToolDispatcher.ToolResult.Success("verified", evidence = evidence)))
        assertFalse(ToolResultVerifier.isVerified(ToolDispatcher.ToolResult.Success("", evidence = evidence)))
    }

    @Test
    fun errorsAreNeverVerified() {
        assertFalse(ToolResultVerifier.isVerified(ToolDispatcher.ToolResult.Error("failed")))
    }
}
