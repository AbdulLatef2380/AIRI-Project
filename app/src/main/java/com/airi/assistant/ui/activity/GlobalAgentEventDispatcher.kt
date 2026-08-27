package com.airi.assistant.ui.activity

import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.ui.viewmodel.ExecutionStage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

object GlobalAgentEventDispatcher {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var started = false

    fun start() {
        if (started) return
        started = true
        wireExecutionStatusBus()
        wireAppEventBus()
    }

    private fun wireExecutionStatusBus() {
        ExecutionStatusBus.status.onEach { state ->
            val (msg, sev) = when (state.executionStage) {
                ExecutionStage.PLANNING    -> "Planning: ${state.activeGoalDescription.take(60).ifBlank { "task" }}" to ActivitySeverity.INFO
                ExecutionStage.EXECUTING   -> "Executing: ${state.activeNodeAction.take(60).ifBlank { state.currentAction.take(60) }}" to ActivitySeverity.INFO
                ExecutionStage.RECOVERING  -> "Recovering (attempt ${state.retryCount}): ${state.recoveryReason.take(60)}" to ActivitySeverity.WARN
                ExecutionStage.REFLECTING  -> "Analysing results…" to ActivitySeverity.INFO
                ExecutionStage.COMPLETED   -> "Execution completed " to ActivitySeverity.INFO
                ExecutionStage.FAILED      -> "Execution failed — ${state.currentAction.take(60)}" to ActivitySeverity.ERROR
                ExecutionStage.CANCELLED   -> "Execution cancelled" to ActivitySeverity.WARN
                ExecutionStage.IDLE        -> return@onEach
            }
            AgentActivityBus.emit(
                ActivityEvent(
                    message = PrivacyGuard.redactForTrace(msg),
                    executionId = state.executionId.takeIf { it.isNotBlank() },
                    category = ActivityCategory.ORCHESTRATION,
                    severity = sev,
                )
            )
        }.launchIn(scope)
    }

    private fun wireAppEventBus() {
        EventBus.events.onEach { event ->
            val ae = mapAppEvent(event) ?: return@onEach
            AgentActivityBus.emit(ae)
        }.launchIn(scope)
    }

    private fun mapAppEvent(event: AppEvent): ActivityEvent? = when (event) {
        is AppEvent.AgentExecutionStarted   -> ActivityEvent(message = "Agent started — ${event.input.take(60)}", category = ActivityCategory.REASONING)
        is AppEvent.AgentExecutionSuccess   -> ActivityEvent(message = "Agent completed in ${event.durationMs}ms", category = ActivityCategory.REASONING)
        is AppEvent.AgentExecutionFailed    -> ActivityEvent(message = "Agent failed: ${event.error.take(80)}", category = ActivityCategory.REASONING, severity = ActivitySeverity.ERROR)
        is AppEvent.AgentExecutionTimeout   -> ActivityEvent(message = "Agent timed out", category = ActivityCategory.REASONING, severity = ActivitySeverity.WARN)
        is AppEvent.AgentExecutionCancelled -> ActivityEvent(message = "Agent cancelled: ${event.reason}", category = ActivityCategory.REASONING, severity = ActivitySeverity.WARN)
        is AppEvent.SkillExecutionStarted   -> ActivityEvent(message = "Running skill: ${event.skillName}", category = ActivityCategory.TOOL)
        is AppEvent.SkillExecutionCompleted -> ActivityEvent(
            message  = "${if (event.success) "" else ""} Skill ${event.skillName} (${event.durationMs}ms)",
            category = ActivityCategory.TOOL,
            severity = if (event.success) ActivitySeverity.INFO else ActivitySeverity.WARN)
        is AppEvent.ToolCallExecuted        -> ActivityEvent(
            message  = "${if (event.success) "" else ""} Tool: ${event.toolName}",
            category = ActivityCategory.TOOL,
            severity = if (event.success) ActivitySeverity.INFO else ActivitySeverity.WARN)
        is AppEvent.RagContextBuilt         -> ActivityEvent(message = "Retrieved memory (${event.hitsCount} hits, ${event.chars} chars)", category = ActivityCategory.MEMORY)
        is AppEvent.ModelGovernanceDecision -> ActivityEvent(message = "Routing → ${event.strategy}: ${event.rationale.take(60)}", category = ActivityCategory.ROUTING)
        is AppEvent.ScheduledJobQueued      -> ActivityEvent(message = "Job queued: ${event.label}", category = ActivityCategory.ORCHESTRATION)
        is AppEvent.ScheduledJobFired       -> ActivityEvent(message = "Job fired: ${event.jobId}", category = ActivityCategory.ORCHESTRATION)
        is AppEvent.PolicyChecked           -> if (!event.passed) ActivityEvent(message = "Policy blocked: ${event.rule}", category = ActivityCategory.SYSTEM, severity = ActivitySeverity.WARN) else null
        is AppEvent.GenericInfo             -> ActivityEvent(message = event.message.take(100), category = ActivityCategory.SYSTEM)
        else -> null
    }

    fun registerCustomSource(source: suspend (emit: suspend (ActivityEvent) -> Unit) -> Unit) {
        kotlinx.coroutines.flow.flow<ActivityEvent> { source { emit(it) } }
            .onEach { AgentActivityBus.emit(it) }
            .launchIn(scope)
    }
}
