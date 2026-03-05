package com.airi.assistant.brain

data class BrainInput(
    val text: String,
    val screenContext: String
)

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)
