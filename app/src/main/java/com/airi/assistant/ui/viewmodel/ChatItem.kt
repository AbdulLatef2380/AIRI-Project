package com.airi.assistant.ui.viewmodel

import com.airi.assistant.agent.trace.TraceNodeState

sealed class ChatItem {
    data class UserMessage(
        val id: String,
        val text: String,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatItem()

    data class AgentTrace(
        val id: String,
        val nodes: List<TraceNodeState>,
        val isCollapsed: Boolean = false
    ) : ChatItem()

    data class AgentFinalResponse(
        val id: String,
        val text: String,
        val isStreaming: Boolean = false,
        val timestamp: Long = System.currentTimeMillis()
    ) : ChatItem()
}
