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
        if (!canRunConcurrently(requests)) {
            return requests.map { Outcome(it, executeOne(it)) }
        }
        return coroutineScope {
            requests.map { request ->
                async { Outcome(request, executeOne(request)) }
            }.awaitAll()
        }
    }

    /**
     * A read-only schema is necessary but not sufficient for concurrency.
     * Providers may express a dependency by referring to a previous call id
     * in an argument (for example `$call-1` or `{{call-1}}`).  Such a batch is
     * kept ordered; unknown/duplicate ids are also fail-closed.
     */
    fun canRunConcurrently(requests: List<Request>): Boolean {
        if (requests.size < 2) return false
        if (requests.any { it.schema.sideEffectPolicy != ToolSchema.SideEffectPolicy.READ_ONLY }) return false
        val ids = requests.map { it.id.trim() }
        if (ids.any { it.isBlank() } || ids.size != ids.toSet().size) return false
        val priorIds = mutableSetOf<String>()
        requests.forEach { request ->
            val argumentText = request.arguments.values.joinToString(" ")
            if (priorIds.any { id ->
                    argumentText.contains("$" + id) ||
                        argumentText.contains("{{" + id + "}}") ||
                        argumentText.contains("call_id=" + id)
                }) return false
            priorIds += request.id
        }
        return true
    }
}
