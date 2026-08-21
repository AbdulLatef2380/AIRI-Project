package com.airi.assistant.agent.planning
import com.airi.core.planning.AgentGoal

// BrainInput is defined in BrainInput.kt — do not redefine here.
// This file contains only BrainOutput.

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)
