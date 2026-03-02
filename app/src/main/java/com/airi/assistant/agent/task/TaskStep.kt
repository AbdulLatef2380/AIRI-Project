package com.airi.assistant.agent.task

sealed class TaskStep {

    data class FocusField(
        val queryHint: String? = null
    ) : TaskStep()

    data class TypeText(
        val text: String
    ) : TaskStep()

    data class ClickButton(
        val keywords: List<String>
    ) : TaskStep()

    object ValidateState : TaskStep()

    object Rollback : TaskStep()
}
