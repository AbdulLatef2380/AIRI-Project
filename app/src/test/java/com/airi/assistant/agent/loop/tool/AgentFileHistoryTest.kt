package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentFileHistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun root() = File(tmp.root, "history")

    @Test fun recordsAndLoadsInOrder() {
        assertTrue(AgentFileHistory.record(root(), "exec-1", "calc.py", null, "v1", 1_000L))
        assertTrue(AgentFileHistory.record(root(), "exec-1", "calc.py", "v1", "v2", 2_000L))
        val loaded = AgentFileHistory.load(root(), "exec-1")
        assertEquals(listOf(1, 2), loaded.map { it.sequence })
        assertNull(loaded[0].before)
        assertEquals("v1", loaded[0].after)
        assertEquals("v1", loaded[1].before)
        assertEquals("v2", loaded[1].after)
        assertEquals(2_000L, loaded[1].timestampMs)
        assertEquals("calc.py", loaded[1].fileName)
    }

    @Test fun executionsAreIsolatedAndTheIdIsNeverAFolderName() {
        AgentFileHistory.record(root(), "../evil", "a.py", null, "x", 1L)
        AgentFileHistory.record(root(), "exec-2", "b.py", null, "y", 2L)
        assertEquals(listOf("a.py"), AgentFileHistory.load(root(), "../evil").map { it.fileName })
        assertEquals(listOf("b.py"), AgentFileHistory.load(root(), "exec-2").map { it.fileName })
        assertTrue(root().listFiles()!!.all { it.name.matches(Regex("[0-9a-f]{32}")) })
    }

    @Test fun refusesBlankIdBadNameAndNoOpWrites() {
        assertFalse(AgentFileHistory.record(root(), "  ", "a.py", null, "x", 1L))
        assertFalse(AgentFileHistory.record(root(), "e", "../a.py", null, "x", 1L))
        assertFalse(AgentFileHistory.record(root(), "e", "run.exe", null, "x", 1L))
        assertFalse(AgentFileHistory.record(root(), "e", "a.py", "same", "same", 1L))
        assertTrue(AgentFileHistory.load(root(), "e").isEmpty())
    }

    @Test fun aRunKeepsAtMostTheSnapshotCap() {
        repeat(AgentFileHistory.MAX_SNAPSHOTS_PER_RUN) { i ->
            assertTrue(AgentFileHistory.record(root(), "e", "a.py", "v$i", "v${i + 1}", i.toLong()))
        }
        assertFalse(AgentFileHistory.record(root(), "e", "a.py", "x", "y", 99L))
        assertEquals(AgentFileHistory.MAX_SNAPSHOTS_PER_RUN, AgentFileHistory.load(root(), "e").size)
    }

    @Test fun anInterruptedSnapshotWithoutItsMetaFileIsNotListed() {
        AgentFileHistory.record(root(), "e", "a.py", null, "v1", 1L)
        val dir = root().listFiles()!!.single()
        File(dir, "002.after").writeText("half written")
        assertEquals(1, AgentFileHistory.load(root(), "e").size)
    }

    @Test fun deleteRemovesOnlyThatExecution() {
        AgentFileHistory.record(root(), "a", "a.py", null, "1", 1L)
        AgentFileHistory.record(root(), "b", "b.py", null, "2", 2L)
        AgentFileHistory.delete(root(), "a")
        assertTrue(AgentFileHistory.load(root(), "a").isEmpty())
        assertEquals(1, AgentFileHistory.load(root(), "b").size)
    }

    @Test fun clearCanKeepRunsThatAreStillInProgress() {
        AgentFileHistory.record(root(), "done", "a.py", null, "1", 1L)
        AgentFileHistory.record(root(), "live", "b.py", null, "2", 2L)
        AgentFileHistory.clear(root(), keepExecutionIds = setOf("live"))
        assertTrue(AgentFileHistory.load(root(), "done").isEmpty())
        assertEquals(1, AgentFileHistory.load(root(), "live").size)
        AgentFileHistory.clear(root())
        assertTrue(AgentFileHistory.load(root(), "live").isEmpty())
    }

    @Test fun pruneKeepsTheMostRecentRuns() {
        AgentFileHistory.record(root(), "old", "a.py", null, "1", 1L)
        AgentFileHistory.record(root(), "mid", "a.py", null, "1", 2L)
        AgentFileHistory.record(root(), "new", "a.py", null, "1", 3L)
        // Folder times are the file system's; make the order explicit.
        File(root(), AgentFileHistory.folderName("old")!!).setLastModified(1_000L)
        File(root(), AgentFileHistory.folderName("mid")!!).setLastModified(2_000L)
        File(root(), AgentFileHistory.folderName("new")!!).setLastModified(3_000L)
        AgentFileHistory.prune(root(), keep = 2)
        assertTrue(AgentFileHistory.load(root(), "old").isEmpty())
        assertEquals(1, AgentFileHistory.load(root(), "mid").size)
        assertEquals(1, AgentFileHistory.load(root(), "new").size)
    }

    @Test fun readExistingOnlyReadsPlainNamesInsideTheFolder() {
        val files = tmp.newFolder("agent_files")
        File(files, "a.py").writeText("print(1)")
        File(tmp.root, "secret.txt").writeText("no")
        assertEquals("print(1)", AgentFileHistory.readExisting(files, "a.py"))
        assertNull(AgentFileHistory.readExisting(files, "missing.py"))
        assertNull(AgentFileHistory.readExisting(files, "../secret.txt"))
        assertNull(AgentFileHistory.readExisting(files, "a.exe"))
    }
}
