package com.airi.assistant.execution

import android.content.Context

/**
 * Minimal execution boundary consumed by AgentLoop.
 *
 * HybridOrchestrator is the production implementation; the boundary keeps
 * AgentLoop tests end-to-end without replacing ToolDispatcher or connector
 * runtime behavior with mocks.
 */
interface AgentOrchestrator {
    suspend fun executeStream(
        request: ExecutionRequest,
        context: Context,
        onToken: suspend (String) -> Unit,
        onComplete: suspend (String, Long, ExecOrigin, CompletionMetadata) -> Unit,
        onError: suspend (String, ExecOrigin) -> Unit,
    )
}
