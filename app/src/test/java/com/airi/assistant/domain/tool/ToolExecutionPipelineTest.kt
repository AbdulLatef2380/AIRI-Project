package com.airi.assistant.domain.tool

import com.airi.assistant.domain.execution.ExecutionOutcome
import com.airi.assistant.domain.execution.RequestId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionPipelineTest {
    private val spec = ToolSpec(
        id = "sample",
        arguments = listOf(
            ArgumentSpec(
                name = "count",
                type = ArgumentSpec.Type.INTEGER,
                required = true,
                constraints = ArgumentSpec.Constraints(minNumber = 1, maxNumber = 10),
            ),
        ),
        capability = "sample",
        risk = ToolSpec.Risk.READ,
        privacy = ToolSpec.PrivacyBoundary.LOCAL_ONLY,
        requiresConfirmation = false,
        idempotent = true,
        handlerKey = "sample",
    )

    @Test
    fun invalidArgumentsNeverReachAuthorizationSandboxOrHandler() = runBlocking {
        var authorizerCalls = 0
        var sandboxCalls = 0
        var handlerCalls = 0
        val outcome = pipeline(
            authorizer = ToolAuthorizer { authorizerCalls++ ; AuthorizationDecision.Allow },
            sandbox = object : ToolSandbox {
                override suspend fun <T> execute(invocation: ValidatedAuthorizedInvocation, block: suspend () -> T): T {
                    sandboxCalls++
                    return block()
                }
            },
            handler = ToolHandler { handlerCalls++ ; ToolHandlerResult.Success("should not run") },
        ).execute(request(mapOf("count" to "not-an-int")))

        assertTrue(outcome is ExecutionOutcome.Failure)
        assertEquals(0, authorizerCalls)
        assertEquals(0, sandboxCalls)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun denyStopsBeforeSandboxAndHandler() = runBlocking {
        var handlerCalls = 0
        val outcome = pipeline(
            authorizer = ToolAuthorizer { AuthorizationDecision.Deny(DenyReason.POLICY_DENIED) },
            handler = ToolHandler { handlerCalls++ ; ToolHandlerResult.Success("should not run") },
        ).execute(request(mapOf("count" to "2")))

        assertEquals("denied:policy_denied", (outcome as ExecutionOutcome.Failure).reason)
        assertEquals(0, handlerCalls)
    }

    @Test
    fun approvalIsBoundToCanonicalArgumentsAndConsumedOnce() = runBlocking {
        val store = InMemoryApprovalTokenStore()
        val token = "approval-1"
        var issued: ApprovalRequest? = null
        val pipeline = ToolExecutionPipeline(
            specs = ToolSpecRegistry { spec },
            authorizer = ToolAuthorizer { invocation ->
                val approval = issued ?: ApprovalRequest(
                    token = token,
                    principal = invocation.request.principal,
                    toolId = invocation.request.toolId,
                    canonicalArguments = invocation.canonicalArguments,
                    sessionId = invocation.request.sessionId,
                    expiresAtEpochMs = System.currentTimeMillis() + 60_000L,
                ).also {
                    issued = it
                    store.issue(it)
                }
                AuthorizationDecision.NeedsApproval(approval)
            },
            approvalTokens = store,
            rateLimiter = FixedWindowToolRateLimiter(10, 60_000L),
            sandbox = object : ToolSandbox {
                override suspend fun <T> execute(invocation: ValidatedAuthorizedInvocation, block: suspend () -> T): T = block()
            },
            handler = ToolHandler { ToolHandlerResult.Success("approved") },
        )

        val first = pipeline.execute(request(mapOf("count" to "2"), approvalToken = token))
        assertTrue(first is ExecutionOutcome.Success)

        val replay = pipeline.execute(request(mapOf("count" to "2"), approvalToken = token))
        assertEquals("approval_invalid", (replay as ExecutionOutcome.Failure).reason)

        val differentArgs = pipeline.execute(request(mapOf("count" to "3"), approvalToken = token))
        assertEquals("approval_invalid", (differentArgs as ExecutionOutcome.Failure).reason)
    }

    @Test
    fun rateLimitPreventsSecondHandlerInvocation() = runBlocking {
        var handlerCalls = 0
        val pipeline = pipeline(
            rateLimiter = FixedWindowToolRateLimiter(1, 60_000L),
            handler = ToolHandler { handlerCalls++ ; ToolHandlerResult.Success("ok") },
        )

        assertTrue(pipeline.execute(request(mapOf("count" to "1"))) is ExecutionOutcome.Success)
        val second = pipeline.execute(request(mapOf("count" to "1")))
        assertEquals("rate_limited", (second as ExecutionOutcome.Failure).reason)
        assertEquals(1, handlerCalls)
    }

    @Test
    fun emptyHandlerResultIsNotReportedAsSuccess() = runBlocking {
        val outcome = pipeline(handler = ToolHandler { ToolHandlerResult.Success("   ") })
            .execute(request(mapOf("count" to "1")))
        assertTrue(outcome is ExecutionOutcome.Empty)
        assertFalse(outcome is ExecutionOutcome.Success)
    }

    private fun pipeline(
        authorizer: ToolAuthorizer = ToolAuthorizer { AuthorizationDecision.Allow },
        approvalTokens: ApprovalTokenStore = ApprovalTokenStore { _, _ -> false },
        rateLimiter: ToolRateLimiter = ToolRateLimiter { _, _ -> true },
        sandbox: ToolSandbox = object : ToolSandbox {
            override suspend fun <T> execute(invocation: ValidatedAuthorizedInvocation, block: suspend () -> T): T = block()
        },
        handler: ToolHandler = ToolHandler { ToolHandlerResult.Success("ok") },
    ) = ToolExecutionPipeline(
        specs = ToolSpecRegistry { id -> if (id == spec.id) spec else null },
        authorizer = authorizer,
        approvalTokens = approvalTokens,
        rateLimiter = rateLimiter,
        sandbox = sandbox,
        handler = handler,
    )

    private fun request(
        args: Map<String, String>,
        approvalToken: String? = null,
    ) = ToolRequest(
        requestId = RequestId.new(),
        generationId = 1L,
        principal = "test-agent",
        sessionId = "session-1",
        toolId = "sample",
        rawArguments = args,
        approvalToken = approvalToken,
    )
}
