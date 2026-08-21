package com.airi.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryPoliciesTest {

    @Test
    fun `ranking excludes entries from another owner`() {
        val request = request(ownerId = "owner-a", sessionId = null)

        val result = MemoryRankingPolicy.rank(
            listOf(
                entry(id = "allowed", ownerId = "owner-a", relevance = 0.8f),
                entry(id = "blocked", ownerId = "owner-b", relevance = 1f)
            ),
            request
        )

        assertEquals(listOf("allowed"), result.map { it.entry.id })
    }

    @Test
    fun `session scoped retrieval excludes another session`() {
        val request = request(ownerId = "owner-a", sessionId = "session-a")

        val result = MemoryRankingPolicy.rank(
            listOf(
                entry(id = "same-session", ownerId = "owner-a", sessionId = "session-a"),
                entry(id = "other-session", ownerId = "owner-a", sessionId = "session-b")
            ),
            request
        )

        assertEquals(listOf("same-session"), result.map { it.entry.id })
    }

    @Test
    fun `ranking never exceeds token budget and skips oversized candidate`() {
        val result = MemoryRankingPolicy.rank(
            listOf(
                entry(id = "oversized", relevance = 1f, tokenCost = 11),
                entry(id = "fits", relevance = 0.7f, tokenCost = 5),
                entry(id = "does-not-fit", relevance = 0.6f, tokenCost = 6)
            ),
            request(tokenBudget = 10)
        )

        assertEquals(listOf("fits"), result.map { it.entry.id })
        assertTrue(result.sumOf { it.entry.tokenCost } <= 10)
    }

    @Test
    fun `ranking uses stable id as deterministic tie breaker`() {
        val result = MemoryRankingPolicy.rank(
            listOf(
                entry(id = "z-entry", relevance = 0.5f),
                entry(id = "a-entry", relevance = 0.5f)
            ),
            request()
        )

        assertEquals(listOf("a-entry", "z-entry"), result.map { it.entry.id })
    }

    @Test
    fun `expired memory is not returned as active`() {
        val expired = entry(id = "expired", expiresAt = NOW)

        assertEquals(MemoryRetentionStatus.EXPIRED, MemoryRetentionPolicy.status(expired, NOW))
        assertFalse(MemoryRetentionPolicy.isActive(expired, NOW))
        assertTrue(MemoryRankingPolicy.rank(listOf(expired), request()).isEmpty())
    }

    @Test
    fun `deletion eligibility takes precedence over expiry`() {
        val entry = entry(
            id = "delete-me",
            createdAt = NOW - 5,
            expiresAt = NOW - 2,
            deleteEligibleAt = NOW - 1
        )

        assertEquals(MemoryRetentionStatus.DELETE_ELIGIBLE, MemoryRetentionPolicy.status(entry, NOW))
        assertTrue(MemoryRetentionPolicy.isDeletionEligible(entry, NOW))
    }

    private fun request(
        ownerId: String = "owner-a",
        sessionId: String? = "session-a",
        tokenBudget: Int = 20
    ) = MemoryRetrievalRequest(
        scope = MemoryScope(ownerId = ownerId, sessionId = sessionId),
        tokenBudget = tokenBudget,
        maxEntries = 5,
        nowEpochMs = NOW
    )

    private fun entry(
        id: String,
        ownerId: String = "owner-a",
        sessionId: String = "session-a",
        relevance: Float = 0.5f,
        tokenCost: Int = 3,
        createdAt: Long = NOW,
        expiresAt: Long? = null,
        deleteEligibleAt: Long? = null
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope(ownerId, sessionId),
        kind = MemoryKind.LONG_TERM,
        content = "Memory $id",
        tokenCost = tokenCost,
        createdAtEpochMs = createdAt,
        expiresAtEpochMs = expiresAt,
        deleteEligibleAtEpochMs = deleteEligibleAt,
        relevanceScore = relevance
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
