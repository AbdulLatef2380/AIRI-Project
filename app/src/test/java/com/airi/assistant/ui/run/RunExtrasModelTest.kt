package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunExtrasModelTest {
    private fun tool(
        name: String,
        params: Map<String, String> = emptyMap(),
        ok: Boolean = true,
        err: String? = null,
        out: String = "",
        ms: Long = 0,
    ) = AgentStep(1, AgentStepType.TOOL, name, inputParams = params, outputSummary = out, success = ok, error = err, durationMs = ms)

    private fun trace(vararg steps: AgentStep, inProgress: Boolean = false, success: Boolean = true, input: String = "build a calculator") =
        AgentTrace(originalInput = input, steps = steps.toList(), success = success, inProgress = inProgress)

    // ── model rows ─────────────────────────────────────────────────────────────────────────

    @Test fun noModelRecordedMeansNoRows() {
        assertTrue(buildModelItems(trace()).isEmpty())
    }

    @Test fun servedModelIsOneTurnRow() {
        val items = buildModelItems(
            trace().copy(resolvedModel = "m1", resolvedProvider = "p1", requestedModel = "m1", requestedProvider = "p1",
                estimatedOutputTokens = 420, firstVisibleTokenLatencyMs = 310)
        )
        assertEquals(1, items.size)
        val turn = items.single() as ModelRunItem.Turn
        assertEquals("m1", turn.model)
        assertEquals(420, turn.outputTokens)
        assertEquals(310L, turn.firstTokenMs)
    }

    @Test fun fallbackAddsASwitchRowBeforeTheTurn() {
        val items = buildModelItems(
            trace().copy(requestedModel = "big", requestedProvider = "cloud", resolvedModel = "small", resolvedProvider = "local",
                fallbackReason = "rate limit")
        )
        assertEquals(2, items.size)
        val switch = items[0] as ModelRunItem.Switch
        assertEquals("cloud / big", switch.from)
        assertEquals("local / small", switch.to)
        assertEquals("rate limit", switch.reason)
        assertTrue(items[1] is ModelRunItem.Turn)
    }

    @Test fun zeroTokensAndLatencyAreHidden() {
        val turn = buildModelItems(trace().copy(model = "m", provider = "p", estimatedOutputTokens = 0, firstVisibleTokenLatencyMs = 0))
            .single() as ModelRunItem.Turn
        assertNull(turn.outputTokens)
        assertNull(turn.firstTokenMs)
    }

    // ── approval card ──────────────────────────────────────────────────────────────────────

    @Test fun destructiveApprovalNeverOffersAllowForRun() {
        val m = ApprovalCardModel("Delete file", "x", destructive = true, requestedAtMs = 0)
        assertEquals(listOf(ApprovalChoice.ALLOW_ONCE, ApprovalChoice.DENY), m.choices)
    }

    @Test fun nonDestructiveApprovalOffersThreeChoices() {
        val m = ApprovalCardModel("Read screen", "x", destructive = false, requestedAtMs = 0)
        assertEquals(listOf(ApprovalChoice.ALLOW_ONCE, ApprovalChoice.ALLOW_THIS_RUN, ApprovalChoice.DENY), m.choices)
    }

    @Test fun countdownRoundsUpAndClamps() {
        val m = ApprovalCardModel("a", "", destructive = true, requestedAtMs = 1_000, timeoutMs = 30_000)
        assertEquals(30, m.remainingSeconds(1_000))
        assertEquals(30, m.remainingSeconds(500))      // clock skew before the request never exceeds the window
        assertEquals(15, m.remainingSeconds(16_000))
        assertEquals(1, m.remainingSeconds(30_500))
        assertEquals(0, m.remainingSeconds(31_000))
        assertEquals(0, m.remainingSeconds(99_000))
    }

    @Test fun approvalAppliesOnlyToTheLiveRunThatOwnsIt() {
        val live = trace(inProgress = true).copy(executionId = "e1")
        assertTrue(approvalAppliesTo(live, "e1"))
        assertFalse(approvalAppliesTo(live, "e2"))
        assertTrue(approvalAppliesTo(live.copy(executionId = ""), "e2"))   // unknown id: live run only
        assertFalse(approvalAppliesTo(trace(inProgress = false).copy(executionId = "e1"), "e1"))
    }

    // ── footer ─────────────────────────────────────────────────────────────────────────────

    @Test fun runningRunHasNoFooter() {
        val f = buildFooter(trace(inProgress = true))
        assertTrue(f.followUps.isEmpty())
        assertFalse(f.canRerun)
    }

    @Test fun failedRunOffersRetryFirst() {
        val f = buildFooter(trace(success = false))
        assertEquals(listOf(FollowUp.RETRY_FAILED, FollowUp.EXPLAIN_STEPS), f.followUps)
        assertTrue(f.canRerun)
    }

    @Test fun successfulRunWithPlanOffersVerify() {
        val plan = AgentStep(0, AgentStepType.PLAN, "plan", outputSummary = "- themes\n- colors")
        assertEquals(listOf(FollowUp.EXPLAIN_STEPS, FollowUp.VERIFY_AGAIN), buildFooter(trace(plan)).followUps)
        assertEquals(listOf(FollowUp.EXPLAIN_STEPS), buildFooter(trace(tool("web_search"))).followUps)
    }

    @Test fun blankInputCannotBeRerun() {
        assertFalse(buildFooter(trace(input = "  ")).canRerun)
    }

    // ── chat run card ──────────────────────────────────────────────────────────────────────

    @Test fun conversationWithoutToolsHasNoCard() {
        assertNull(buildRunCardModel(trace()))
        val intentOnly = AgentStep(0, AgentStepType.INTENT, "intent", outputSummary = "chat")
        assertNull(buildRunCardModel(trace(intentOnly)))
    }

    @Test fun cardStepCountMatchesTimelineRows() {
        val t = trace(
            AgentStep(0, AgentStepType.PLAN, "plan", outputSummary = "- a\n- b"),
            tool("load_skill"),
            tool("write_file", mapOf("filename" to "calculator.py")),
            tool("run_command", mapOf("command" to "python -m py_compile calculator.py")),
        )
        val card = buildRunCardModel(t)!!
        assertEquals(buildTimeline(t).size, card.stepCount)
        assertEquals(3, card.stepCount)
        assertEquals(RunCardStatus.COMPLETED, card.status)
        assertEquals("build a calculator", card.title)
    }

    @Test fun cardStatusFollowsTrace() {
        assertEquals(RunCardStatus.RUNNING, buildRunCardModel(trace(tool("a"), inProgress = true))!!.status)
        assertEquals(RunCardStatus.FAILED, buildRunCardModel(trace(tool("a"), success = false))!!.status)
        assertNull(buildRunCardModel(trace(tool("a"), inProgress = true))!!.elapsedMs)
    }

    // ── workspace sheet ────────────────────────────────────────────────────────────────────

    @Test fun workspaceListsWrittenFilesOnceAndCommandsWithOutput() {
        val rows = buildTimeline(
            trace(
                tool("read_file", mapOf("filename" to "calculator.py")),
                tool("write_file", mapOf("filename" to "calculator.py")),
                tool("run_command", mapOf("command" to "python calculator.py"), out = "42", ms = 2_000),
            )
        )
        val ws = buildWorkspaceSheet(rows)
        assertEquals(1, ws.files.size)
        assertTrue(ws.files.single().written)
        assertEquals(1, ws.terminal.size)
        assertEquals("42", ws.terminal.single().output)
        assertEquals(2_000L, ws.terminal.single().durationMs)
        assertFalse(ws.isEmpty)
    }

    @Test fun failedWriteIsNotCountedAsWritten() {
        val ws = buildWorkspaceSheet(
            buildTimeline(trace(tool("write_file", mapOf("filename" to "a.py"), ok = false, err = "disk full"), success = false))
        )
        val file = ws.files.single()
        assertTrue(file.failed)
        assertFalse(file.written)
    }

    @Test fun failedCommandShowsItsError() {
        val ws = buildWorkspaceSheet(
            buildTimeline(trace(tool("run_command", mapOf("command" to "python x.py"), ok = false, err = "SyntaxError"), success = false))
        )
        assertTrue(ws.terminal.single().failed)
        assertEquals("SyntaxError", ws.terminal.single().output)
    }

    @Test fun noFileOrCommandStepsMeansEmptySheet() {
        assertNotNull(buildWorkspaceSheet(buildTimeline(trace(tool("web_search", mapOf("query" to "x"))))))
        assertTrue(buildWorkspaceSheet(buildTimeline(trace(tool("web_search", mapOf("query" to "x"))))).isEmpty)
    }
}
