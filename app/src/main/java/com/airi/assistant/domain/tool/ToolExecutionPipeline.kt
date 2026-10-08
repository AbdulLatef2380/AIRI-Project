package com.airi.assistant.domain.tool

import com.airi.assistant.domain.execution.ExecutionOutcome
import com.airi.assistant.domain.execution.RequestId
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap

/** A request before parsing and authorization. */
data class ToolRequest(
    val requestId: RequestId,
    val generationId: Long,
    val principal: String,
    val sessionId: String,
    val toolId: String,
    val rawArguments: Map<String, String>,
    val approvalToken: String? = null,
) {
    init {
        require(generationId >= 0L)
        require(principal.isNotBlank())
        require(sessionId.isNotBlank())
        require(toolId.isNotBlank())
    }
}

/** The result after strict parsing, before policy/authorization. */
data class ValidatedInvocation(
    val request: ToolRequest,
    val spec: ToolSpec,
    val arguments: StrictArgumentValidator.ValidatedArguments,
    val canonicalArguments: String,
)

/**
 * Capability to execute an already validated and authorized invocation.
 * A handler never receives raw arguments.
 */
class ValidatedAuthorizedInvocation private constructor(
    val request: ToolRequest,
    val spec: ToolSpec,
    val arguments: StrictArgumentValidator.ValidatedArguments,
    val canonicalArguments: String,
    val approval: ApprovalRequest? = null,
) {
    companion object {
        internal fun create(
            request: ToolRequest,
            spec: ToolSpec,
            arguments: StrictArgumentValidator.ValidatedArguments,
            canonicalArguments: String,
            approval: ApprovalRequest?,
        ) = ValidatedAuthorizedInvocation(request, spec, arguments, canonicalArguments, approval)
    }
}

fun interface ToolSpecRegistry {
    fun find(toolId: String): ToolSpec?
}

fun interface ToolAuthorizer {
    fun authorize(invocation: ValidatedInvocation): AuthorizationDecision
}

fun interface ApprovalTokenStore {
    fun consume(request: ApprovalRequest, invocation: ValidatedInvocation): Boolean
}

fun interface ToolRateLimiter {
    fun tryAcquire(principal: String, toolId: String): Boolean
}

/** Sandbox is deliberately mandatory in the pipeline constructor. */
interface ToolSandbox {
    suspend fun <T> execute(
        invocation: ValidatedAuthorizedInvocation,
        block: suspend () -> T,
    ): T
}

fun interface ToolHandler {
    suspend fun execute(invocation: ValidatedAuthorizedInvocation): ToolHandlerResult
}

sealed interface ToolHandlerResult {
    data class Success(val text: String) : ToolHandlerResult
    data object Empty : ToolHandlerResult
    data class Failure(val reason: String) : ToolHandlerResult
    data object Timeout : ToolHandlerResult
}

/**
 * The only generic execution path for a ToolSpec-backed action.
 *
 * parse → validate → authorization → approval → rate limit → sandbox → handler
 *
 * Any policy/approval/rate-limit exception is fail-closed. Cancellation is
 * deliberately rethrown so coroutine cancellation remains real cancellation.
 */
class ToolExecutionPipeline(
    private val specs: ToolSpecRegistry,
    private val authorizer: ToolAuthorizer,
    private val approvalTokens: ApprovalTokenStore,
    private val rateLimiter: ToolRateLimiter,
    private val sandbox: ToolSandbox,
    private val handler: ToolHandler,
) {
    suspend fun execute(request: ToolRequest): ExecutionOutcome {
        val spec = specs.find(request.toolId)
            ?: return ExecutionOutcome.Failure(request.requestId, request.generationId, "unknown_tool")

        val validation = StrictArgumentValidator.validate(spec, request.rawArguments)
        val arguments = validation.arguments
            ?: return ExecutionOutcome.Failure(
                request.requestId,
                request.generationId,
                "invalid_arguments:${validation.failure?.message ?: "unknown"}",
            )
        val validated = ValidatedInvocation(
            request = request,
            spec = spec,
            arguments = arguments,
            canonicalArguments = canonicalArguments(arguments.canonical),
        )

        val decision = runCatching { authorizer.authorize(validated) }.getOrElse {
            return ExecutionOutcome.Failure(request.requestId, request.generationId, "authorization_failed")
        }
        val approval = when (decision) {
            AuthorizationDecision.Allow -> null
            is AuthorizationDecision.Deny -> return ExecutionOutcome.Failure(
                request.requestId,
                request.generationId,
                "denied:${decision.reason.name.lowercase()}",
            )
            is AuthorizationDecision.NeedsApproval -> {
                val provided = request.approvalToken
                if (provided.isNullOrBlank() || provided != decision.request.token) {
                    return ExecutionOutcome.Failure(request.requestId, request.generationId, "approval_required")
                }
                val consumed = runCatching {
                    approvalTokens.consume(decision.request, validated)
                }.getOrElse { false }
                if (!consumed) {
                    return ExecutionOutcome.Failure(request.requestId, request.generationId, "approval_invalid")
                }
                decision.request
            }
        }

        val admitted = runCatching {
            rateLimiter.tryAcquire(request.principal, request.toolId)
        }.getOrElse { false }
        if (!admitted) {
            return ExecutionOutcome.Failure(request.requestId, request.generationId, "rate_limited")
        }

        val authorized = ValidatedAuthorizedInvocation.create(
            request = request,
            spec = spec,
            arguments = arguments,
            canonicalArguments = validated.canonicalArguments,
            approval = approval,
        )
        return try {
            when (val result = sandbox.execute(authorized) { handler.execute(authorized) }) {
                is ToolHandlerResult.Success -> if (result.text.isBlank()) {
                    ExecutionOutcome.Empty(request.requestId, request.generationId)
                } else {
                    ExecutionOutcome.Success(request.requestId, request.generationId, result.text)
                }
                ToolHandlerResult.Empty -> ExecutionOutcome.Empty(request.requestId, request.generationId)
                is ToolHandlerResult.Failure -> ExecutionOutcome.Failure(request.requestId, request.generationId, result.reason)
                ToolHandlerResult.Timeout -> ExecutionOutcome.Timeout(request.requestId, request.generationId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ExecutionOutcome.Failure(
                request.requestId,
                request.generationId,
                "handler_failed:${error::class.simpleName ?: "unknown"}",
            )
        }
    }

    private fun canonicalArguments(arguments: Map<String, String>): String =
        arguments.toSortedMap().entries.joinToString("&") { (key, value) ->
            "${escape(key)}=${escape(value)}"
        }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '&' -> append("\\&")
                '=' -> append("\\=")
                else -> append(char)
            }
        }
    }
}

/** One-shot, argument-bound approval implementation for JVM and production adapters. */
class InMemoryApprovalTokenStore : ApprovalTokenStore {
    private val pending = ConcurrentHashMap<String, ApprovalRequest>()

    fun issue(request: ApprovalRequest) {
        pending[request.token] = request
    }

    override fun consume(request: ApprovalRequest, invocation: ValidatedInvocation): Boolean {
        val now = System.currentTimeMillis()
        val stored = pending[request.token] ?: return false
        if (stored != request || stored.expiresAtEpochMs < now) return false
        if (stored.principal != invocation.request.principal ||
            stored.toolId != invocation.request.toolId ||
            stored.sessionId != invocation.request.sessionId ||
            stored.canonicalArguments != invocation.canonicalArguments
        ) return false
        return pending.remove(request.token, stored)
    }
}

/** Fixed-window limiter used by the pipeline; callers can replace it in tests or production. */
class FixedWindowToolRateLimiter(
    private val maxCalls: Int,
    private val windowMs: Long,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : ToolRateLimiter {
    init {
        require(maxCalls > 0)
        require(windowMs > 0)
    }

    private data class Window(var startedAt: Long, var calls: Int)
    private val windows = ConcurrentHashMap<String, Window>()

    override fun tryAcquire(principal: String, toolId: String): Boolean {
        val key = "$principal\u0000$toolId"
        val now = clockMs()
        val window = windows.computeIfAbsent(key) { Window(now, 0) }
        synchronized(window) {
            if (now - window.startedAt >= windowMs) {
                window.startedAt = now
                window.calls = 0
            }
            if (window.calls >= maxCalls) return false
            window.calls++
            return true
        }
    }
}
