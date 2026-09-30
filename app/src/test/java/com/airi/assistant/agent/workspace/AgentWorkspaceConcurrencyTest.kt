package com.airi.assistant.agent.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentWorkspaceConcurrencyTest {
    @Test
    fun parallelLinksRemainReadableWithoutConcurrentModification() = runBlocking {
        val workspace = AgentWorkspace("parallel-workspace-test")
        val writerCount = 8
        val uniqueLinksPerWriter = 20
        val repeatedLinksPerWriter = 80
        workspace.putText("shared-artifact", "shared", producerTaskId = "shared-producer")

        (0 until writerCount).map { writer ->
            async(Dispatchers.Default) {
                repeat(uniqueLinksPerWriter) { index ->
                    val key = "artifact-$writer-$index"
                    workspace.putText(key, "value-$writer-$index", producerTaskId = "producer-$writer")
                    workspace.link("producer-$writer", key, "consumer")
                }
                repeat(repeatedLinksPerWriter) {
                    workspace.link("shared-producer", "shared-artifact", "consumer")
                }
            }
        }.awaitAll()

        val resolved = workspace.resolveDependency("consumer")
        assertEquals(1 + writerCount * uniqueLinksPerWriter, resolved.size)
        assertEquals(writerCount * (uniqueLinksPerWriter + repeatedLinksPerWriter), workspace.snapshot().edgeCount)
        workspace.clear()
    }

    @Test
    fun concurrentArtifactWritesNeverExceedTheWorkspaceCapacity() = runBlocking {
        val workspace = AgentWorkspace("workspace-capacity-test")
        val writerCount = 8
        val writesPerWriter = 60

        (0 until writerCount).map { writer ->
            async(Dispatchers.Default) {
                repeat(writesPerWriter) { index ->
                    val key = "capacity-$writer-$index"
                    workspace.putText(key, "payload", producerTaskId = "producer-$writer")
                }
            }
        }.awaitAll()

        assertEquals(200, workspace.snapshot().artifacts.size)
        workspace.clear()
    }
}
