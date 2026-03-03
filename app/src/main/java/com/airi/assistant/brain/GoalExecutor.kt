package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal

interface GoalExecutor {
    fun executeGoal(goal: AgentGoal)
}
