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
        assumeTrue("host ProcessHandle support is required for process-tree test", isProcessAlive(childPid!!))
        assertTrue("child process should be alive before cleanup", isProcessAlive(childPid))

        ProcessTreeTerminator.terminate(root)
        assertTrue("root should terminate promptly", root.waitFor(3, TimeUnit.SECONDS))

        val stoppedAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (isProcessAlive(childPid) && System.nanoTime() < stoppedAt) Thread.sleep(25)
        assertFalse("descendant remained alive after parent cleanup", isProcessAlive(childPid))
    }

    /**
     * Android's bootclasspath does not expose ProcessHandle even when CI uses
     * JDK 17. Match ProcessTreeTerminator's reflection boundary so this host
     * process test compiles for Android and still exercises the real API where
     * the JVM provides it.
     */
    private fun isProcessAlive(pid: Long): Boolean = runCatching {
        val handleClass = Class.forName("java.lang.ProcessHandle")
        val optional = handleClass.getMethod("of", Long::class.javaPrimitiveType).invoke(null, pid)
        val handle = optional.javaClass.getMethod("get").invoke(optional)
        handle.javaClass.getMethod("isAlive").invoke(handle) as Boolean
    }.getOrDefault(false)
}
