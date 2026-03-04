package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal

class GoalExecutor {

    suspend fun executeGoal(goal: AgentGoal): Boolean {
        return true
    }
}
