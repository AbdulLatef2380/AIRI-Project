package com.airi.core.chain

import kotlinx.coroutines.*

class TaskChainer(
    private val retryPolicy: RetryPolicy = RetryPolicy()
) {

    private val goals = mutableListOf<AgentGoal>()
    private val strategySelector = StrategySelector()

    fun addGoal(goal: AgentGoal) {
        goals.add(goal)
    }

    suspend fun execute(
        executor: suspend (AgentGoal, AdaptiveStrategy) -> Unit,
        contextProvider: suspend () -> String,
        verifierProvider: (AgentGoal) -> (suspend () -> Boolean),
        failureStrategyProvider: (AgentGoal) -> FailureStrategy = { FailureStrategy.RETRY }
    ) {

        for (goal in goals) {

            var attempt = 0
            var success = false

            while (attempt < retryPolicy.maxAttempts && !success) {

                val currentContext = contextProvider()

                val strategy = strategySelector.selectStrategy(
                    goal = goal,
                    currentContext = currentContext,
                    attempt = attempt
                )

                executor(goal, strategy)

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
