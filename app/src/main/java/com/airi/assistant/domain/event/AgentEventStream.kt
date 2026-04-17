package com.airi.assistant.domain.event

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter

object AgentEventStream {

    val agentEvents: Flow<AppEvent> = EventBus.events.filter { event ->
        event is AppEvent.AgentExecutionStarted  ||
        event is AppEvent.AgentExecutionSuccess  ||
        event is AppEvent.AgentExecutionFailed   ||
        event is AppEvent.AgentExecutionTimeout  ||
        event is AppEvent.AgentExecutionCancelled
    }

    val policyEvents: Flow<AppEvent> = EventBus.events.filter {
        it is AppEvent.PolicyChecked
    }

    val skillEvents: Flow<AppEvent> = EventBus.events.filter {
        it is AppEvent.SkillExecutionStarted ||
        it is AppEvent.SkillExecutionCompleted ||
        it is AppEvent.ToolCallExecuted
    }

    val authEvents: Flow<AppEvent> = EventBus.events.filter {
        it is AppEvent.UserSignedIn ||
        it is AppEvent.UserSignedOut ||
        it is AppEvent.AuthFailed
    }

    val monetizationEvents: Flow<AppEvent> = EventBus.events.filter {
        it is AppEvent.SubscriptionChecked ||
        it is AppEvent.UsageLimitReached   ||
        it is AppEvent.PremiumRequired
    }

    val allEvents: Flow<AppEvent> = EventBus.events
}
