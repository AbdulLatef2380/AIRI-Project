package com.airi.assistant.agent.loop.tool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Executes a parsed tool batch according to the canonical schema policy.
 * Only independent READ_ONLY calls are concurrent; every other batch is
 * deliberately serialized to preserve side-effect and dependency safety.
 */
object ToolCallBatchExecutor {
    data class Request(
        val id: String,
        val toolName: String,
        val arguments: Map<String, String>,
        val schema: ToolSchema,
    )

    data class Outcome<T>(
        val request: Request,
        val value: T,
    )

    suspend fun <T> execute(
        requests: List<Request>,
        executeOne: suspend (Request) -> T,
    ): List<Outcome<T>> {
        if (requests.size < 2 || requests.any { it.schema.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY }) {
            return requests.map { Outcome(it, executeOne(it)) }
        }
        return coroutineScope {
            requests.map { request ->
                async { Outcome(request, executeOne(request)) }
            }.awaitAll()
        }
    }
}
