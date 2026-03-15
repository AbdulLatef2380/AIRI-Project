package com.airi.assistant.agent.decision

import kotlin.random.Random

object DecisionEngine {

    private var epsilon = 0.25   // 25% exploration كبداية
    private const val decayRate = 0.995
    private const val minEpsilon = 0.05

    fun <T> select(
        scoredItems: List<Pair<T, Int>>
    ): T? {

        if (scoredItems.isEmpty()) return null

        val explore = Random.nextDouble() < epsilon

        val selected = if (explore) {
            scoredItems.random().first
        } else {
            scoredItems.maxByOrNull { it.second }?.first
        }

        decayEpsilon()

        return selected
    }

    private fun decayEpsilon() {
        epsilon *= decayRate
        if (epsilon < minEpsilon) {
            epsilon = minEpsilon
        }
    }
}
