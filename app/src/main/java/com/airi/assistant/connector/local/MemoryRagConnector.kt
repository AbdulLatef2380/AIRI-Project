package com.airi.assistant.connector.local

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.memory.rag.RagRetriever
import com.airi.assistant.memory.repository.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * MemoryRagConnector — exposes the on-device RAG memory pipeline to the agent tool layer.
 *
 * This connector bridges [RagRetriever] and [MemoryManager] into the standard
 * [Connector] interface so any agent node in the UCL graph can perform semantic
 * memory retrieval as a tool call — identical to how a web-search connector
 * works, but pointing at the local Room + EmbeddingService backend.
 *
 * ## Why this matters
 * Previously the RAG pipeline was wired ONLY in ChatViewModel.sendMessage().
 * The UCL agent graph had NO access to memory when executing sub-tasks.
 * This connector gives every agent node first-class RAG access.
 *
 * ## Supported actions
 * | action              | params                     | notes                                   |
 * |---------------------|----------------------------|-----------------------------------------|
 * | `semantic_search`   | `query`, `k` (default 5)   | Cosine-sim retrieval via EmbeddingService|
 * | `recent_messages`   | `session_id`, `limit`      | Chronological fallback retrieval         |
 * | `build_context`     | `query`, `session_id`, `k` | Full RAG block ready to prepend to prompt|
 * | `memory_status`     | —                          | Is semantic memory loaded and ready?     |
 */
class MemoryRagConnector(
    private val ragRetriever: RagRetriever,
    private val memoryManager: MemoryManager,
) : Connector {

    override val id          = "memory_rag"
    override val name        = "Memory / RAG"
    override val description = "On-device semantic memory retrieval — RAG over past conversations."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, healthy = false, statusLine = "Initialising…")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("memory", "rag", "embedding", "retrieval", "semantic", "local"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val ready = ragRetriever.isReady()
        _state.value = ConnectorState(
            connected = true,
            healthy   = ready,
            statusLine = if (ready) "Semantic memory ready" else "No embedding model loaded — using chronological fallback",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        Log.i("AIRI_PROOF", "MEMORY_RAG_CONNECTOR_CONNECT ready=$ready")
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (input.action) {
            "semantic_search" -> semanticSearch(input)
            "recent_messages" -> recentMessages(input)
            "build_context"   -> buildContext(input)
            "memory_status"   -> memoryStatus()
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "MemoryRagConnector: unknown action '${input.action}'",
            )
        }
    }

    private suspend fun semanticSearch(input: ConnectorInput): ConnectorOutput {
        val query = input.params["query"].orEmpty().ifBlank { input.text }
        val k     = input.params["k"]?.toIntOrNull() ?: 5
        val sessionId = input.params["session_id"].orEmpty()

        if (query.isBlank()) {
            return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'query' param")
        }

        if (!ragRetriever.isReady()) {
            Log.i("AIRI_PROOF", "MEMORY_RAG_SEMANTIC_SKIP reason=not_ready fallback=chronological")
            return recentMessages(input)
        }

        return runCatching {
            val block = ragRetriever.buildContextBlock(
                sessionId = sessionId,
                query     = query,
                k         = k,
            )
            Log.i("AIRI_PROOF", "MEMORY_RAG_SEMANTIC_SEARCH query='${query.take(60)}' k=$k chars=${block.length}")
            ConnectorOutput.Success(
                text = block,
                data = mapOf("query" to query, "k" to k.toString(), "chars" to block.length.toString()),
            )
        }.getOrElse { t ->
            Log.w("AIRI_PROOF", "MEMORY_RAG_SEMANTIC_FAIL cause=${t.message}")
            ConnectorOutput.Failure(
                code = "retrieval_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private suspend fun recentMessages(input: ConnectorInput): ConnectorOutput {
        val sessionId = input.params["session_id"].orEmpty()
        val limit     = input.params["limit"]?.toIntOrNull() ?: 10

        return runCatching {
            val msgs = memoryManager.getRecentMessages(sessionId, limit)
            val formatted = msgs.joinToString("\n") { m ->
                "[${m.role.uppercase()}] ${m.content.take(200)}"
            }
            Log.i("AIRI_PROOF", "MEMORY_RAG_RECENT_MESSAGES count=${msgs.size} session=$sessionId")
            ConnectorOutput.Success(
                text = formatted,
                data = mapOf("count" to msgs.size.toString(), "session_id" to sessionId),
            )
        }.getOrElse { t ->
            ConnectorOutput.Failure(
                code = "memory_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private suspend fun buildContext(input: ConnectorInput): ConnectorOutput {
        val query     = input.params["query"].orEmpty().ifBlank { input.text }
        val sessionId = input.params["session_id"].orEmpty()
        val k         = input.params["k"]?.toIntOrNull() ?: 5

        if (query.isBlank()) {
            return ConnectorOutput.Failure(code = "bad_input", message = "Missing 'query' param")
        }

        return runCatching {
            val block = ragRetriever.buildContextBlock(sessionId = sessionId, query = query, k = k)
            Log.i("AIRI_PROOF", "MEMORY_RAG_BUILD_CONTEXT query='${query.take(60)}' chars=${block.length}")
            ConnectorOutput.Success(
                text = block,
                data = mapOf("ready_to_inject" to "true", "chars" to block.length.toString()),
            )
        }.getOrElse { t ->
            ConnectorOutput.Failure(
                code = "context_build_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private fun memoryStatus(): ConnectorOutput {
        val ready  = ragRetriever.isReady()
        val status = if (ready) "Semantic memory ready — embedding model loaded" else "No embedding model — chronological fallback active"
        return ConnectorOutput.Success(
            text = status,
            data = mapOf("semantic_ready" to ready.toString()),
        )
    }
}
