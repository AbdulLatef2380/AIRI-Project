package com.airi.core.memory

import kotlin.math.exp

data class MemoryRetrievalRequest(
    val scope: MemoryScope,
    val tokenBudget: Int,
    val maxEntries: Int,
    val nowEpochMs: Long,
    val recencyHalfLifeMs: Long = DEFAULT_RECENCY_HALF_LIFE_MS
) {
    init {
        require(tokenBudget >= 0) { "tokenBudget must not be negative" }
        require(maxEntries >= 0) { "maxEntries must not be negative" }
        require(nowEpochMs >= 0) { "nowEpochMs must not be negative" }
        require(recencyHalfLifeMs > 0) { "recencyHalfLifeMs must be positive" }
    }

    private companion object {
        const val DEFAULT_RECENCY_HALF_LIFE_MS = 24L * 60L * 60L * 1_000L
    }
}

data class RankedMemory(
    val entry: MemoryEntry,
    val score: Float
)

object MemoryRankingPolicy {

    fun rank(entries: Iterable<MemoryEntry>, request: MemoryRetrievalRequest): List<RankedMemory> {
        if (request.tokenBudget == 0 || request.maxEntries == 0) return emptyList()

        val ranked = entries
            .asSequence()
            .filter { it.matches(request.scope) }
            .filter { MemoryRetentionPolicy.isActive(it, request.nowEpochMs) }
            .map { RankedMemory(it, score(it, request)) }
            .sortedWith(
                compareByDescending<RankedMemory> { it.score }
                    .thenByDescending { it.entry.createdAtEpochMs }
                    .thenBy { it.entry.id }
            )

        val selected = ArrayList<RankedMemory>(request.maxEntries)
        var tokensUsed = 0
        for (candidate in ranked) {
            if (selected.size == request.maxEntries) break
            if (candidate.entry.tokenCost > request.tokenBudget - tokensUsed) continue
            selected += candidate
            tokensUsed += candidate.entry.tokenCost
        }
        return selected
    }

    fun score(entry: MemoryEntry, request: MemoryRetrievalRequest): Float {
        val ageMs = (request.nowEpochMs - entry.createdAtEpochMs).coerceAtLeast(0L)
        val recency = exp(
            -LN_2 * ageMs.toDouble() / request.recencyHalfLifeMs.toDouble()
        ).toFloat()
        val outcomeFactor = when (entry.outcome) {
            MemoryOutcome.SUCCESS -> SUCCESS_OUTCOME_FACTOR
            MemoryOutcome.FAILURE -> FAILURE_OUTCOME_FACTOR
            MemoryOutcome.UNKNOWN -> 1f
        }
        val recallFactor = if (entry.recallCount >= RECALL_BOOST_THRESHOLD) RECALL_BOOST_FACTOR else 1f
        val base = (
            RELEVANCE_WEIGHT * entry.relevanceScore.coerceIn(0f, 1f) +
                IMPORTANCE_WEIGHT * entry.importanceScore.coerceIn(0f, 1f) +
                RECENCY_WEIGHT * recency
            )
        return (base * outcomeFactor * recallFactor).coerceIn(0f, MAX_SCORE)
    }

    private fun MemoryEntry.matches(requestScope: MemoryScope): Boolean =
        scope.ownerId == requestScope.ownerId &&
            (requestScope.sessionId == null || scope.sessionId == requestScope.sessionId)

    private const val LN_2 = 0.6931471805599453
    private const val RELEVANCE_WEIGHT = 0.55f
    private const val IMPORTANCE_WEIGHT = 0.25f
    private const val RECENCY_WEIGHT = 0.20f
    private const val SUCCESS_OUTCOME_FACTOR = 1.10f
    private const val FAILURE_OUTCOME_FACTOR = 0.75f
    private const val RECALL_BOOST_THRESHOLD = 3
    private const val RECALL_BOOST_FACTOR = 1.05f
    private const val MAX_SCORE = 1.5f
}
