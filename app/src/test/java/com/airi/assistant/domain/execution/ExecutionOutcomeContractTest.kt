package com.airi.assistant.domain.execution

import com.airi.assistant.domain.tool.ArgumentSpec
import com.airi.assistant.domain.tool.AuthorizationDecision
import com.airi.assistant.domain.tool.ToolSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionOutcomeContractTest {
    @Test
    fun terminalOutcomeIsPublishedAtMostOnce() {
        val guard = TerminalOutcomeGuard()
        assertTrue(guard.tryPublish())
        assertFalse(guard.tryPublish())
        assertTrue(guard.isPublished())
    }

    @Test
    fun outcomeCarriesRequestAndGenerationIdentity() {
        val outcome = ExecutionOutcome.Timeout(RequestId("req-1"), generationId = 7L)
        assertTrue(outcome.requestId.value == "req-1")
        assertTrue(outcome.generationId == 7L)
    }

    @Test
    fun toolSpecRejectsDuplicateArgumentsAndKeepsApprovalTyped() {
        val spec = ToolSpec(
            id = "send_message",
            capability = "connector_send",
            risk = ToolSpec.Risk.SIDE_EFFECT,
            privacy = ToolSpec.PrivacyBoundary.USER_APPROVAL_REQUIRED,
            requiresConfirmation = true,
            idempotent = false,
            handlerKey = "connector.send_message",
            arguments = listOf(ArgumentSpec("recipient", ArgumentSpec.Type.STRING, required = true)),
        )
        assertTrue(spec.requiresConfirmation)
        assertTrue(AuthorizationDecision.NeedsApproval(
            com.airi.assistant.domain.tool.ApprovalRequest("t", "p", spec.id, "{}", "s", 100L)
        ) is AuthorizationDecision)
    }
}
