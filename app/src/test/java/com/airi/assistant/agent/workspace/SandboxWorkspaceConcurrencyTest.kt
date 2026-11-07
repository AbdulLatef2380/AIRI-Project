package com.airi.assistant.agent.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SandboxWorkspaceConcurrencyTest {
    @Test
    fun concurrentAppendsAreNotLost() = runBlocking {
        val workspace = SandboxWorkspace("append-${UUID.randomUUID()}")
        val writers = 8
        val appendsPerWriter = 100

        (0 until writers).map {
            async(Dispatchers.Default) {
                repeat(appendsPerWriter) {
                    workspace.appendFile("/shared.txt", "x")
                }
            }
        }.awaitAll()

        val result = workspace.readFile("/shared.txt") as WorkspaceResult.Ok
        assertEquals(writers * appendsPerWriter, result.content.length)
    }

    @Test
    fun freshWorkspaceWithoutSnapshotIsNotPrunedAsStale() {
        val goalId = "fresh-${UUID.randomUUID()}"
        WorkspaceRegistry.get(goalId)
        try {
            WorkspaceRegistry.pruneStale(maxAgeMs = 60_000L)
            assertTrue(WorkspaceRegistry.activeCount > 0)
        } finally {
            WorkspaceRegistry.release(goalId)
        }
    }
}
