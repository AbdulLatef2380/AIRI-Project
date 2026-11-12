package com.airi.assistant.agent.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProcessTreeTerminatorTest {
    @Test
    fun terminationStopsDescendantAndRootProcesses() {
        val root = runCatching {
            ProcessBuilder("sh", "-c", "sleep 60 & echo \$!; wait").start()
        }.getOrNull()
        assumeTrue("host shell is required for process-tree test", root != null)
        root!!
        val childPid = root.inputStream.bufferedReader().readLine()?.toLongOrNull()
        assumeTrue("child pid was not emitted", childPid != null)
        val child = ProcessHandle.of(childPid!!).orElseThrow()
        assertTrue("child process should be alive before cleanup", child.isAlive)

        ProcessTreeTerminator.terminate(root)
        assertTrue("root should terminate promptly", root.waitFor(3, TimeUnit.SECONDS))

        val stoppedAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (child.isAlive && System.nanoTime() < stoppedAt) Thread.sleep(25)
        assertFalse("descendant remained alive after parent cleanup", child.isAlive)
    }
}
