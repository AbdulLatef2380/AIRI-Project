package com.airi.assistant.agent.multiagent

import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * SharedCognitiveBus — inter-agent communication channel.
 *
 * Agents publish [AgentMessage]s to the bus; other agents subscribe
 * to topics they care about. The bus is the ONLY communication path
 * between agents — no direct references.
 *
 * Integrated with [AgentActivityBus] for user-visible orchestration feed.
 */
object SharedCognitiveBus {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _messages = MutableSharedFlow<AgentMessage>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val messages: SharedFlow<AgentMessage> = _messages.asSharedFlow()

    fun publish(message: AgentMessage) {
        scope.launch {
            _messages.emit(message)
            AgentActivityBus.emit(
                message  = "[${message.fromAgentId}→${message.topic}] ${message.summary.take(60)}",
                category = ActivityCategory.ORCHESTRATION
            )
        }
    }

    fun publishResult(fromAgentId: String, topic: String, payload: Any?, summary: String) {
        publish(AgentMessage(
            fromAgentId = fromAgentId,
            topic       = topic,
            payload     = payload,
            summary     = summary,
            type        = AgentMessage.Type.RESULT
        ))
    }

    fun publishRequest(fromAgentId: String, toAgentId: String, topic: String, payload: Any?, summary: String) {
        publish(AgentMessage(
            fromAgentId = fromAgentId,
            toAgentId   = toAgentId,
            topic       = topic,
            payload     = payload,
            summary     = summary,
            type        = AgentMessage.Type.REQUEST
        ))
    }

    fun publishError(fromAgentId: String, topic: String, error: String) {
        publish(AgentMessage(
            fromAgentId = fromAgentId,
            topic       = topic,
            payload     = error,
            summary     = "Error: ${error.take(80)}",
            type        = AgentMessage.Type.ERROR
        ))
    }
}

data class AgentMessage(
    val fromAgentId: String,
    val toAgentId:   String?  = null,   // null = broadcast
    val topic:       String,
    val payload:     Any?     = null,
    val summary:     String,
    val type:        Type     = Type.EVENT,
    val timestampMs: Long     = System.currentTimeMillis(),
    val correlationId: String? = null   // links requests to responses
) {
    enum class Type { REQUEST, RESULT, EVENT, ERROR, CAPABILITY_QUERY }
}
