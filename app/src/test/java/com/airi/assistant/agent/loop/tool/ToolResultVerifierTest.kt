package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultVerifierTest {
    private val writeSchema = ToolSchema(
        name = "write_file",
        description = "write",
        sideEffectPolicy = ToolSchema.SideEffectPolicy.REQUIRES_DURABLE_CONTEXT,
    )

    @Test
    fun sideEffectSuccessRequiresSemanticEvidence() {
        val result = ToolDispatcher.ToolResult.Success(
            output = "saved",
            evidence = ToolDispatcher.ToolResult.Evidence(
                kind = ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF,
                changedFiles = listOf("report.md"),
                semanticVerified = true,
                semanticReference = "report.md",
            ),
        )
        assertTrue(ToolResultVerifier.verify("write_file", writeSchema, result).passed)
    }

    @Test
    fun sideEffectSuccessWithoutSemanticEvidenceIsRejected() {
        val result = ToolDispatcher.ToolResult.Success(
            output = "saved",
            evidence = ToolDispatcher.ToolResult.Evidence(
                kind = ToolDispatcher.ToolResult.Evidence.Kind.FILE_DIFF,
                changedFiles = listOf("report.md"),
            ),
        )
        val decision = ToolResultVerifier.verify("write_file", writeSchema, result)
        assertFalse(decision.passed)
        assertEquals("evidence_required", decision.code)
    }

    @Test
    fun handlerFailureCannotBecomeVerifiedCompletion() {
        val decision = ToolResultVerifier.verify("write_file", writeSchema, ToolDispatcher.ToolResult.Error("disk full"))
        assertFalse(decision.passed)
        assertEquals(ToolErrorCodes.EXECUTION_FAILED, decision.code)
    }

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
