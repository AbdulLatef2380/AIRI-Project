package com.airi.assistant.agent.trace

/** A bounded, user-facing description of one agent step. */
sealed class TraceStep {
    data class Planning(val tasks: List<TaskItem>, val completedCount: Int) : TraceStep()
    data class WebSearch(val query: String, val sourcesCount: Int, val urls: List<String> = emptyList()) : TraceStep()
    data class UiInteraction(val action: String, val targetDescription: String = "") : TraceStep()
    data class ScreenRead(val description: String) : TraceStep()
    data class MemoryAccess(val query: String, val resultSummary: String = "") : TraceStep()
    data class DeviceAction(val toolName: String, val description: String) : TraceStep()
    data class ConfirmationRequest(val question: String) : TraceStep()
    data class Thinking(val thought: String) : TraceStep()
    data class StepError(val originalStep: TraceStep, val errorMessage: String) : TraceStep()
}

data class TaskItem(val label: String, val state: TaskState = TaskState.PENDING)
enum class TaskState { PENDING, IN_PROGRESS, DONE }
