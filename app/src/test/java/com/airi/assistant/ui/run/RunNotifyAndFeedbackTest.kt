package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunNotifyAndFeedbackTest {
    private val start = 1_000_000L
    private fun step(ts: Long) = AgentStep(1, AgentStepType.TOOL, "write_file", inputParams = mapOf("filename" to "a.py"), timestampMs = ts)
    private fun trace(
        durationMs: Long = 30_000,
        success: Boolean = true,
        inProgress: Boolean = false,
        result: String = "done",
        steps: List<AgentStep> = listOf(step(start + durationMs)),
    ) = AgentTrace(originalInput = "make a calculator", steps = steps, finalResult = result, success = success, timestamp = start, inProgress = inProgress)

    // ── notification policy ────────────────────────────────────────────────────────────────
    @Test fun longFinishedRunInTheBackgroundNotifies() {
        assertTrue(LongRunNotificationPolicy.shouldNotify(trace(), appInForeground = false))
        assertTrue(LongRunNotificationPolicy.shouldNotify(trace(success = false, result = "network error"), appInForeground = false))
    }

    @Test fun foregroundShortRunningOrToollessRunsDoNotNotify() {
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(), appInForeground = true))
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(durationMs = 5_000), appInForeground = false))
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(inProgress = true), appInForeground = false))
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(steps = emptyList()), appInForeground = false))
    }

    @Test fun aRunTheUserStoppedDoesNotNotify() {
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(success = false, result = "Cancelled"), appInForeground = false))
    }

    @Test fun thresholdIsInclusive() {
        assertTrue(LongRunNotificationPolicy.shouldNotify(trace(durationMs = LongRunNotificationPolicy.MIN_DURATION_MS), false))
        assertFalse(LongRunNotificationPolicy.shouldNotify(trace(durationMs = LongRunNotificationPolicy.MIN_DURATION_MS - 1), false))
    }

    @Test fun notificationIdIsStablePerRun() {
        assertEquals(AgentRunNotifier.notificationId("trace-1"), AgentRunNotifier.notificationId("trace-1"))
    }

    // ── rating reasons ─────────────────────────────────────────────────────────────────────
    @Test fun reasonsAreOfferedOnlyAfterAThumbsDown() {
        assertTrue(ratingReasonsFor(1).isEmpty())
        assertTrue(ratingReasonsFor(0).isEmpty())
        assertEquals(RatingReason.entries.toList(), ratingReasonsFor(-1))
    }

    @Test fun codecRoundTripsAndDropsBadLines() {
        val map = RunFeedbackCodec.update(RunFeedbackCodec.decode(null), "trace-a", RatingReason.SLOW)
        RunFeedbackCodec.update(map, "trace-b", RatingReason.OTHER)
        val decoded = RunFeedbackCodec.decode(RunFeedbackCodec.encode(map) + "\nbroken\nx|UNKNOWN\nbad id|SLOW")
        assertEquals(mapOf("trace-a" to RatingReason.SLOW, "trace-b" to RatingReason.OTHER), decoded)
    }

    @Test fun updatingReplacesAndNullClears() {
        val map = RunFeedbackCodec.decode(null)
        RunFeedbackCodec.update(map, "r", RatingReason.SLOW)
        RunFeedbackCodec.update(map, "r", RatingReason.INCOMPLETE)
        assertEquals(RatingReason.INCOMPLETE, map["r"])
        RunFeedbackCodec.update(map, "r", null)
        assertNull(map["r"])
    }

    @Test fun unsafeIdsAreNotStoredAndOldestEntriesFallOut() {
        val map = RunFeedbackCodec.decode(null)
        RunFeedbackCodec.update(map, "a|b", RatingReason.SLOW)
        RunFeedbackCodec.update(map, "line\nbreak", RatingReason.SLOW)
        assertTrue(map.isEmpty())
        repeat(RunFeedbackCodec.MAX_ENTRIES + 5) { RunFeedbackCodec.update(map, "id$it", RatingReason.OTHER) }
        assertEquals(RunFeedbackCodec.MAX_ENTRIES, map.size)
        assertFalse(map.containsKey("id0"))
        assertTrue(map.containsKey("id${RunFeedbackCodec.MAX_ENTRIES + 4}"))
    }

    // ── removal hook ───────────────────────────────────────────────────────────────────────
    @Test fun removalHookReportsWhatEachDeleteDropped() {
        val manager = AgentTraceManager()
        val seen = mutableListOf<Pair<List<String>, AgentTraceManager.TraceRemoval>>()
        manager.onTracesRemoved = { removed, kind -> seen += removed.map { it.id } to kind }
        manager.recordChatExecution(trace().copy(id = "t1"))
        manager.recordChatExecution(trace().copy(id = "t2", sessionId = "s2"))
        manager.deleteTrace("t1")
        manager.deleteForSession("s2")
        manager.recordChatExecution(trace().copy(id = "t3"))
        manager.clearTraces()
        manager.recordChatExecution(trace().copy(id = "t4"))
        manager.clearAllTraces()
        assertEquals(
            listOf(
                listOf("t1") to AgentTraceManager.TraceRemoval.SOME,
                listOf("t2") to AgentTraceManager.TraceRemoval.SOME,
                listOf("t3") to AgentTraceManager.TraceRemoval.CLEAR_LOGS,
                listOf("t4") to AgentTraceManager.TraceRemoval.WIPE,
            ),
            seen,
        )
    }

    @Test fun deletingAnUnknownTraceDoesNotCallTheHook() {
        val manager = AgentTraceManager()
        var calls = 0
        manager.onTracesRemoved = { _, _ -> calls++ }
        manager.deleteTrace("nope")
        assertEquals(0, calls)
    }

    // ── approval notification (Part 5 §1) ───────────────────────────────────────────────────
    @Test fun approvalNotifiesOnlyInTheBackgroundWithAKnownExecution() {
        assertTrue(ApprovalNotificationPolicy.shouldNotify(appInForeground = false, executionId = "exec-1"))
        assertFalse(ApprovalNotificationPolicy.shouldNotify(appInForeground = true, executionId = "exec-1"))
        assertFalse(ApprovalNotificationPolicy.shouldNotify(appInForeground = false, executionId = ""))
    }

    @Test fun approvalIdIsStablePerExecutionAndApartFromCompletionIds() {
        assertEquals(AgentRunNotifier.approvalNotificationId("exec-1"), AgentRunNotifier.approvalNotificationId("exec-1"))
        assertTrue(AgentRunNotifier.approvalNotificationId("exec-1") != AgentRunNotifier.notificationId("exec-1"))
    }

    @Test fun approvalLinkTargetsTheLiveTraceOfTheExecution() {
        val runId = com.airi.assistant.ai.agent.trace.LiveTraceProjector.traceIdFor("exec-1")
        assertEquals(runId, AgentRunDeepLink.parse(AgentRunDeepLink.build(runId)))
    }
}
