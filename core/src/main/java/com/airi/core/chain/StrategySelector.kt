package com.airi.core.chain

class StrategySelector {

    fun selectStrategy(
        goal: AgentGoal,
        currentContext: String,
        attempt: Int
    ): AdaptiveStrategy {

        if (attempt == 0) {
            return AdaptiveStrategy.DirectAction
        }

        if (currentContext.length < 20) {
            return AdaptiveStrategy.WaitAndRecheck
        }

        if (!currentContext.contains(goal.description, ignoreCase = true)) {
            return AdaptiveStrategy.ScrollAndRetry
        }

        return AdaptiveStrategy.FallbackPath
    }
}
