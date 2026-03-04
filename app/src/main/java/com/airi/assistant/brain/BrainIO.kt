package com.airi.assistant.brain

sealed class BrainInput {
    data class ScreenContext(val content: String) : BrainInput()
    data class UserCommand(val text: String) : BrainInput()
}

sealed class BrainOutput {
    data class ExecuteGoal(val goal: AgentGoal) : BrainOutput()
    data class Speak(val text: String) : BrainOutput()
}
