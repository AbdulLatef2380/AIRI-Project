package com.airi.assistant.ai.prompt.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptSizeReportTest {
    @Test
    fun includedPartsAreShownButNotCounted() {
        val report = PromptSizeReport()
            .add("system_prompt", "a".repeat(400))
            .addIncluded("rag", "b".repeat(100))
            .add("tool_block", "c".repeat(200))
            .addChars("history", 400)

        assertEquals(1000, report.totalChars)
        assertEquals(250, report.totalEstimatedTokens)
        val line = report.toLogLine()
        assertTrue(line, line.contains("[rag=100c]"))
        assertTrue(line, line.endsWith("total=1000c/~250t"))
    }

    @Test
    fun nullAndNegativeSizesBecomeZero() {
        val report = PromptSizeReport().add("rag", null).addChars("history", -5)
        assertEquals(0, report.totalChars)
        assertEquals("rag=0c,history=0c total=0c/~0t", report.toLogLine())
    }

    @Test
    fun timelineReportsTimePerStageNotRunningTotal() {
        var now = 1_000L
        val timeline = PreFirstTokenTimeline { now }
        now += 40; timeline.mark("history_load")
        now += 300; timeline.mark("rag_knowledge")
        now += 5; timeline.mark("base_prompt")

        assertEquals("history_load=40ms,rag_knowledge=300ms,base_prompt=5ms total=345ms", timeline.toLogLine())
        assertEquals(345L, timeline.totalMs())
    }

    @Test
    fun clockGoingBackwardsNeverProducesNegativeStages() {
        var now = 5_000L
        val timeline = PreFirstTokenTimeline { now }
        now = 4_900L; timeline.mark("stage")
        assertEquals("stage=0ms total=0ms", timeline.toLogLine())
    }
}
