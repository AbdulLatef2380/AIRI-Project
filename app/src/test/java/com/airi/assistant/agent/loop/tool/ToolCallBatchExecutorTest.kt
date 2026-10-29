package com.airi.assistant.agent.loop.tool

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class ToolCallBatchExecutorTest {
    @Test
    fun independentReadsOverlap() = runBlocking {
        val active = java.util.concurrent.atomic.AtomicInteger(0)
        val peak = java.util.concurrent.atomic.AtomicInteger(0)
        val requests = listOf("a", "b").map { name ->
            ToolCallBatchExecutor.Request(name, name, emptyMap(), ToolSchema(name, "read"))
        }
        ToolCallBatchExecutor.execute(requests) {
            val now = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, now) }
            delay(25)
            active.decrementAndGet()
            it.toolName
        }
        assertTrue("independent reads must overlap", peak.get() >= 2)
    }

    @Test
    fun writesRemainOrdered() = runBlocking {
        val order = Collections.synchronizedList(mutableListOf<String>())
        val writeSchema = ToolSchema(
            name = "write",
            description = "write",
            sideEffectPolicy = ToolSchema.SideEffectPolicy.REQUIRES_USER_CONFIRMATION,
        )
        val requests = listOf("first", "second").map { name ->
            ToolCallBatchExecutor.Request(name, name, emptyMap(), writeSchema)
        }
        ToolCallBatchExecutor.execute(requests) {
            order += it.toolName
            delay(5)
            it.toolName
        }
        assertEquals(listOf("first", "second"), order)
    }
}
