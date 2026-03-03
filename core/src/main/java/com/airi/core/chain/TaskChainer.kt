package com.airi.core.chain

class TaskChainer {

    private val goals = mutableListOf<AgentGoal>()

    fun addGoal(goal: AgentGoal) {
        goals.add(goal)
    }

    fun execute(executor: (AgentGoal) -> Unit) {
        for (goal in goals) {
            executor(goal)
        }
    }
}
