package com.airi.core.chain

import kotlinx.coroutines.*

class TaskChainer(
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {

    private val goals = mutableListOf<AgentGoal>()

    fun addGoal(goal: AgentGoal) {
        goals.add(goal)
    }

    suspend fun execute(
        executor: suspend (AgentGoal) -> Unit,
        verifierProvider: (AgentGoal) -> (suspend () -> Boolean),
        failureStrategyProvider: (AgentGoal) -> FailureStrategy = { FailureStrategy.RETRY }
    ) {

        for (goal in goals) {

            var attempt = 0
            var success = false

            while (attempt < retryPolicy.maxAttempts && !success) {

                executor(goal)

                val verifier = TemporalVerifier()

                val result = verifier.verify(
                    expectedCondition = verifierProvider(goal)
                )

                if (result.state == GoalExecutionState.SUCCESS) {
                    success = true
                } else {
                    attempt++
                    delay(retryPolicy.delayBetweenAttempts)
                }
            }

            if (!success) {
                when (failureStrategyProvider(goal)) {
                    FailureStrategy.RETRY -> continue
                    FailureStrategy.SKIP -> continue
                    FailureStrategy.ABORT -> break
                }
            }
        }
    }
}
