package com.airi.assistant.brain

open class GoalExecutor {

    open suspend fun executeGoal(goal: AgentGoal): Boolean {
        return true
    }
}
