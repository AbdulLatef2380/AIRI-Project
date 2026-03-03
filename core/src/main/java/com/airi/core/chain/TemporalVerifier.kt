package com.airi.core.chain

class TemporalVerifier(
    private val timeoutMillis: Long = 5000
) {

    suspend fun verify(
        expectedCondition: suspend () -> Boolean,
        interval: Long = 300
    ): ExecutionResult {

        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMillis) {

            if (expectedCondition()) {
                return ExecutionResult(
                    state = GoalExecutionState.SUCCESS,
                    message = "Condition satisfied"
                )
            }

            kotlinx.coroutines.delay(interval)
        }

        return ExecutionResult(
            state = GoalExecutionState.TIMEOUT,
            message = "Verification timed out"
        )
    }
}
