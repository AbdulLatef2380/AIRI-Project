package com.airi.assistant.ui.run.code

import com.airi.assistant.agent.loop.tool.AgentFileHistory.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileHistoryModelTest {
    private fun snap(seq: Int, name: String, before: String?, after: String, ts: Long = seq * 1000L) =
        Snapshot(seq, ts, name, before, after)

    @Test fun noWritesMeansNoHistory() {
        assertNull(buildFileHistory("a.py", emptyList()))
        assertNull(buildFileHistory("a.py", listOf(snap(1, "b.py", null, "x"))))
    }

    @Test fun createdFileHasNoOriginal() {
        val history = buildFileHistory("a.py", listOf(snap(1, "a.py", null, "v1")))!!
        assertTrue(history.createdInRun)
        assertNull(history.original)
        assertEquals("v1", history.current)
        assertEquals(1, history.writes)
    }

    @Test fun overwrittenFileKeepsTheContentBeforeTheFirstWriteOfTheRun() {
        val history = buildFileHistory(
            "a.py",
            listOf(
                snap(3, "a.py", "v2", "v3"),
                snap(1, "a.py", "v0", "v1"),
                snap(2, "a.py", "v1", "v2"),
                snap(4, "other.py", null, "zzz"),
            ),
        )!!
        assertFalse(history.createdInRun)
        assertEquals("v0", history.original)
        assertEquals("v3", history.current)
        assertEquals(3, history.writes)
    }

    @Test fun replayFramesAreOrderedAndNumbered() {
        val frames = buildReplayFrames(listOf(snap(2, "b.py", null, "B"), snap(1, "a.py", null, "A")))
        assertEquals(listOf("a.py", "b.py"), frames.map { it.fileName })
        assertEquals(listOf(0, 1), frames.map { it.index })
        assertTrue(frames.all { it.total == 2 })
    }

    @Test fun liveFrameIsTheNewestAndClampingIsSafe() {
        val frames = buildReplayFrames(listOf(snap(1, "a.py", null, "A"), snap(2, "a.py", "A", "AB")))
        assertEquals(1, liveFrameIndex(frames))
        assertEquals(0, clampFrameIndex(-5, frames))
        assertEquals(1, clampFrameIndex(99, frames))
        assertEquals(0, liveFrameIndex(emptyList()))
        assertEquals(0, clampFrameIndex(3, emptyList()))
    }

    @Test fun frameDiffShowsOnlyWhatThatWriteChanged() {
        val frame = buildReplayFrames(listOf(snap(1, "a.py", "a\nb", "a\nb\nc"))).single()
        val diff = frameDiff(frame)
        assertEquals(1, diff.added)
        assertEquals(0, diff.removed)
    }

    @Test fun firstFrameOfACreatedFileDiffsAgainstNothing() {
        val diff = frameDiff(buildReplayFrames(listOf(snap(1, "a.py", null, "x\ny"))).single())
        assertEquals(2, diff.added)
    }
}
