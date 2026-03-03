package com.airi.core.chain

import kotlinx.coroutines.*

class TaskChainer {

    private val goals = mutableListOf<AgentGoal>()

    fun addGoal(goal: AgentGoal) {
        goals.add(goal)
    }

    suspend fun execute(
        executor: suspend (AgentGoal) -> Unit,
        verifierProvider: (AgentGoal) -> (suspend () -> Boolean)
    ) {

        for (goal in goals) {

            executor(goal)

            val verifier = TemporalVerifier()

            val result = verifier.verify(
                expectedCondition = verifierProvider(goal)
            )

            if (result.state != GoalExecutionState.SUCCESS) {
                break
            }
        }
    }
}
