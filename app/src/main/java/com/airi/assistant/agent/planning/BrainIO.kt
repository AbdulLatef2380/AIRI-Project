package com.airi.assistant.agent.planning

// BrainInput is defined in BrainInput.kt — do not redefine here.
// This file contains only BrainOutput.

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)
