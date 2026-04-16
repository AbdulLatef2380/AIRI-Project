package com.airi.assistant.agent.planning

open class GoalExecutor {

    open suspend fun executeGoal(goal: AgentGoal): Boolean {
        return true
    }
}
