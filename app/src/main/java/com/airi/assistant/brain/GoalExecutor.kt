package com.airi.assistant.brain

interface GoalExecutor {
    suspend fun executeGoal(goal: AgentGoal): Boolean
}
