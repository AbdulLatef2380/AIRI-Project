package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal

interface GoalExecutor {
    suspend fun executeGoal(goal: AgentGoal): Boolean
}
