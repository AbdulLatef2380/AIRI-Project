package com.airi.assistant.ui.viewmodel

import com.airi.assistant.ai.agent.trace.AgentTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MergeRunSourcesTest {
    private fun run(id: String, at: Long, live: Boolean = false) =
        AgentTrace(id = id, originalInput = id, success = !live, timestamp = at, inProgress = live)

    @Test fun nothingFromRoomLeavesTheMemoryListUntouched() {
        val memory = listOf(run("a", 1), run("b", 2))
        assertSame(memory, mergeRunSources(memory, emptyList(), 50))
    }

    @Test fun aRunInMemoryWinsOverItsRoomCopy() {
        val memory = listOf(run("a", 1))
        assertSame(memory, mergeRunSources(memory, listOf(run("a", 1)), 50))
    }

    @Test fun roomOnlyRunsJoinNewestFirst() {
        val merged = mergeRunSources(listOf(run("b", 20)), listOf(run("a", 10), run("c", 30)), 50)
        assertEquals(listOf("c", "b", "a"), merged.map { it.id })
    }

    @Test fun theRetentionLimitBoundsTheListAndKeepsLiveRuns() {
        val memory = listOf(run("live", 5, live = true), run("m", 20))
        val merged = mergeRunSources(memory, listOf(run("old1", 10), run("old2", 9)), limit = 3)
        assertEquals(listOf("live", "m", "old1"), merged.map { it.id })
    }
}
