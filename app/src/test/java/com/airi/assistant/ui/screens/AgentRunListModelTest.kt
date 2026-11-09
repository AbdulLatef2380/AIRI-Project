package com.airi.assistant.ui.screens

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunListModelTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private val now: Long = Calendar.getInstance(utc).apply { set(2026, Calendar.NOVEMBER, 9, 12, 0, 0) }.timeInMillis
    private val day = 24L * 60 * 60 * 1000

    private fun tool(name: String, ok: Boolean = true, ms: Long = 1000) =
        AgentStep(stepIndex = 0, type = AgentStepType.TOOL, name = name, success = ok, durationMs = ms)

    private fun trace(
        input: String = "task",
        at: Long = now,
        success: Boolean = true,
        inProgress: Boolean = false,
        steps: List<AgentStep> = emptyList(),
        result: String = "",
    ) = AgentTrace(originalInput = input, timestamp = at, success = success, inProgress = inProgress, steps = steps, finalResult = result)

    @Test fun statusMapsFromTraceFlags() {
        assertEquals(RunStatus.RUNNING, trace(inProgress = true).runStatus())
        assertEquals(RunStatus.COMPLETED, trace(success = true).runStatus())
        assertEquals(RunStatus.FAILED, trace(success = false).runStatus())
    }

    @Test fun countersClassifyFilesCommandsAndTools() {
        val c = trace(steps = listOf(tool("write_file"), tool("read_file"), tool("run_python"), tool("web_search"))).runCounters()
        assertEquals(RunCounters(files = 2, commands = 1, tools = 1), c)
    }

    @Test fun nonToolStepsAreNotCounted() {
        val steps = listOf(AgentStep(0, AgentStepType.PLAN, "plan"), AgentStep(1, AgentStepType.VERIFY, "verify"))
        assertEquals(0, trace(steps = steps).runCounters().total)
    }

    @Test fun durationSumsStepDurations() {
        assertEquals(3000L, trace(steps = listOf(tool("a", ms = 1000), tool("b", ms = 2000))).runDurationMs())
    }

    @Test fun titleIsFirstNonBlankLine() {
        assertEquals("build calculator", trace(input = "\n  build calculator \nmore").runTitle())
    }

    @Test fun previewFlattensMarkdownAndTruncates() {
        val p = trace(result = "# Title\n\n**bold** `code`\n" + "x".repeat(300)).resultPreview(40)
        assertTrue(p.length <= 40)
        assertTrue('\n' !in p && '*' !in p && '`' !in p && '#' !in p)
    }

    @Test fun filtersApply() {
        val ok = trace(steps = listOf(tool("write_file")))
        val failed = trace(success = false)
        val live = trace(inProgress = true)
        val all = listOf(ok, failed, live)
        fun ids(f: RunFilter) = buildRunList(all, "", f, now, utc).flatMap { it.runs }.map { it.id }.toSet()
        assertEquals(setOf(ok.id, failed.id, live.id), ids(RunFilter.ALL))
        assertEquals(setOf(live.id), ids(RunFilter.RUNNING))
        assertEquals(setOf(failed.id), ids(RunFilter.FAILED))
        assertEquals(setOf(ok.id), ids(RunFilter.HAS_FILES))
    }

    @Test fun searchMatchesInputResultAndModel() {
        val a = trace(input = "Calculator app")
        val b = trace(input = "x", result = "done calculator")
        val c = trace(input = "unrelated")
        val got = buildRunList(listOf(a, b, c), "calc", RunFilter.ALL, now, utc).flatMap { it.runs }.map { it.id }.toSet()
        assertEquals(setOf(a.id, b.id), got)
    }

    @Test fun groupsByDayWithTodayAndYesterdayBuckets() {
        val t = trace(at = now)
        val y = trace(at = now - day)
        val old = trace(at = now - 5 * day)
        val g = buildRunList(listOf(old, y, t), "", RunFilter.ALL, now, utc)
        assertEquals(listOf(RunDayBucket.TODAY, RunDayBucket.YESTERDAY, RunDayBucket.OTHER), g.map { it.bucket })
    }

    @Test fun runningRunIsPinnedFirstEvenIfOlder() {
        val newer = trace(at = now)
        val liveOld = trace(at = now - 3 * day, inProgress = true)
        val g = buildRunList(listOf(newer, liveOld), "", RunFilter.ALL, now, utc)
        assertEquals(liveOld.id, g.first().runs.first().id)
        assertEquals(RunDayBucket.TODAY, g.first().bucket)
    }

    @Test fun emptyWhenNothingMatches() {
        assertTrue(buildRunList(listOf(trace()), "zzz", RunFilter.ALL, now, utc).isEmpty())
    }
}
