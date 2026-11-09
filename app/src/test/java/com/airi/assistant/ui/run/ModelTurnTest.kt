package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.ChatExecutionTraceMapper
import com.airi.assistant.ai.agent.trace.ModelTurnRecord
import com.airi.assistant.ai.agent.trace.ModelTurnSteps
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Part 5 §3.4: per-turn model rows, from the recorded call to the interleaved feed. */
class ModelTurnTest {
    private fun record(
        turn: Int,
        ts: Long,
        provider: String = "p1",
        model: String = "m1",
        requestedProvider: String = "p1",
        requestedModel: String = "m1",
        toolChoice: String = ModelTurnRecord.TOOL_CHOICE_AUTO,
        finish: String = "TOOL_CALLS",
        fallback: String? = null,
    ) = ModelTurnRecord(
        turn = turn, timestampMs = ts, durationMs = 1_500, providerId = provider, modelId = model,
        requestedProviderId = requestedProvider, requestedModelId = requestedModel, toolChoice = toolChoice,
        finishReason = finish, promptTokens = 900, outputTokens = 60, fallbackReason = fallback,
    )

    private fun traceWith(vararg steps: AgentStep, inProgress: Boolean = false) =
        AgentTrace(originalInput = "x", steps = steps.toList(), success = true, inProgress = inProgress)

    private fun tool(name: String, ts: Long) =
        AgentStep(0, AgentStepType.TOOL, name, inputParams = mapOf("filename" to "a.py"), timestampMs = ts)

    // ── encoding ───────────────────────────────────────────────────────────────────────────
    @Test fun aRecordSurvivesTheStepEncoding() {
        val step = ModelTurnSteps.toStep(record(2, 5_000, toolChoice = "required", finish = "STOP", fallback = "rate limit"))
        assertEquals(AgentStepType.MODEL_TURN, step.type)
        val view = ModelTurnSteps.view(step)!!
        assertEquals(2, view.turn)
        assertEquals("required", view.toolChoice)
        assertEquals("STOP", view.finishReason)
        assertEquals(900, view.promptTokens)
        assertEquals(60, view.outputTokens)
        assertEquals(1_500L, view.durationMs)
        assertEquals("rate limit", view.fallbackReason)
    }

    @Test fun otherStepsAreNotModelTurns() {
        assertNull(ModelTurnSteps.view(tool("write_file", 1)))
    }

    @Test fun theFallbackReasonPassesThroughTheRedactor() {
        val step = ModelTurnSteps.toStep(record(1, 1, fallback = "key sk-secret failed")) { it.replace("sk-secret", "[redacted]") }
        assertEquals("key [redacted] failed", ModelTurnSteps.view(step)!!.fallbackReason)
    }

    // ── counters stay about work ───────────────────────────────────────────────────────────
    @Test fun modelTurnsDoNotChangeStepCountersOrErrorState() {
        val t = traceWith(ModelTurnSteps.toStep(record(1, 1)), tool("write_file", 2), ModelTurnSteps.toStep(record(2, 3)))
        assertEquals(1, t.stepCount)
        assertEquals(1, t.successCount)
        assertFalse(t.hasErrors)
    }

    @Test fun modelTurnsAreNotTimelineRows() {
        val t = traceWith(ModelTurnSteps.toStep(record(1, 1)), tool("write_file", 2))
        assertEquals(1, buildTimeline(t).size)
    }

    @Test fun theNewestWorkRowStaysRunningEvenWhenATurnFollowsIt() {
        val t = traceWith(tool("write_file", 2), ModelTurnSteps.toStep(record(2, 3)), inProgress = true)
        assertEquals(StepStatus.RUNNING, buildTimeline(t).single().status)
    }

    // ── model rows ─────────────────────────────────────────────────────────────────────────
    @Test fun eachRecordedTurnIsItsOwnRow() {
        val t = traceWith(ModelTurnSteps.toStep(record(1, 10)), tool("write_file", 20), ModelTurnSteps.toStep(record(2, 30, finish = "STOP")))
        val items = buildModelItems(t)
        assertEquals(2, items.size)
        val first = items[0] as ModelRunItem.TurnDetail
        val second = items[1] as ModelRunItem.TurnDetail
        assertEquals(TurnFinish.TOOL_CALLS, first.finish)
        assertEquals(TurnFinish.STOP, second.finish)
        assertEquals(listOf(1, 2), listOf(first.turn, second.turn))
    }

    @Test fun requiredToolChoiceIsFlagged() {
        val items = buildModelItems(traceWith(ModelTurnSteps.toStep(record(1, 10, toolChoice = "required"))))
        assertTrue((items.single() as ModelRunItem.TurnDetail).toolRequired)
    }

    @Test fun aSwitchRowAppearsOnlyWhereTheServingTargetChanges() {
        val t = traceWith(
            ModelTurnSteps.toStep(record(1, 10)),
            ModelTurnSteps.toStep(record(2, 20, provider = "p2", model = "m2", fallback = "rate limit")),
            ModelTurnSteps.toStep(record(3, 30, provider = "p2", model = "m2")),
        )
        val items = buildModelItems(t)
        assertEquals(listOf("TurnDetail", "Switch", "TurnDetail", "TurnDetail"), items.map { it::class.simpleName })
        val switch = items[1] as ModelRunItem.Switch
        assertEquals("p1 / m1", switch.from)
        assertEquals("p2 / m2", switch.to)
        assertEquals("rate limit", switch.reason)
    }

    @Test fun aFirstTurnServedByAnotherTargetThanRequestedGetsASwitchRow() {
        val t = traceWith(ModelTurnSteps.toStep(record(1, 10, provider = "p2", model = "m2", requestedProvider = "p1", requestedModel = "m1")))
        assertTrue(buildModelItems(t)[0] is ModelRunItem.Switch)
    }

    @Test fun aRunWithoutRecordedTurnsKeepsTheRunLevelRow() {
        val t = traceWith(tool("write_file", 1)).copy(resolvedModel = "m1", resolvedProvider = "p1", estimatedOutputTokens = 40)
        assertTrue(buildModelItems(t).single() is ModelRunItem.Turn)
    }

    @Test fun unknownFinishReasonsAreNotWorded() {
        assertEquals(TurnFinish.UNKNOWN, TurnFinish.parse("SOMETHING_NEW"))
        assertEquals(TurnFinish.UNKNOWN, TurnFinish.parse(null))
        assertEquals(TurnFinish.LENGTH_LIMIT, TurnFinish.parse("length_limit"))
    }

    // ── feed ───────────────────────────────────────────────────────────────────────────────
    @Test fun feedInterleavesTurnsAndWorkRowsByTime() {
        val t = traceWith(
            ModelTurnSteps.toStep(record(1, 10)), tool("write_file", 20),
            ModelTurnSteps.toStep(record(2, 30)), tool("run_python", 40),
        )
        val feed = buildFeed(buildTimeline(t), buildModelItems(t))
        assertEquals(listOf("Model", "Step", "Model", "Step"), feed.map { it::class.simpleName })
    }

    @Test fun aTurnEndingWithItsToolCallStillComesFirst() {
        val t = traceWith(tool("write_file", 20), ModelTurnSteps.toStep(record(1, 20)))
        val feed = buildFeed(buildTimeline(t), buildModelItems(t))
        assertTrue(feed[0] is FeedItem.Model)
    }

    @Test fun feedKeysAreUnique() {
        val t = traceWith(ModelTurnSteps.toStep(record(1, 10)), tool("a", 20), tool("a", 20), ModelTurnSteps.toStep(record(2, 30)))
        val keys = buildFeed(buildTimeline(t), buildModelItems(t)).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    // ── bus store and mapper ───────────────────────────────────────────────────────────────
    @Test fun theBusKeepsTurnsPerOpenExecutionAndDropsOthers() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("goal", 1, "turns-exec-1")
        ExecutionStatusBus.recordModelTurn("turns-exec-1", record(1, 1))
        ExecutionStatusBus.recordModelTurn("unknown-exec", record(1, 1))
        assertEquals(1, ExecutionStatusBus.modelTurns("turns-exec-1").size)
        assertTrue(ExecutionStatusBus.modelTurns("unknown-exec").isEmpty())
        assertTrue(ExecutionStatusBus.modelTurns("").isEmpty())
        ExecutionStatusBus.reset()
        assertTrue(ExecutionStatusBus.modelTurns("turns-exec-1").isEmpty())
    }

    @Test fun theBusCapsTurnsPerRun() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("goal", 1, "turns-exec-cap")
        repeat(ModelTurnSteps.MAX_TURNS + 10) { ExecutionStatusBus.recordModelTurn("turns-exec-cap", record(it + 1, it.toLong())) }
        assertEquals(ModelTurnSteps.MAX_TURNS, ExecutionStatusBus.modelTurns("turns-exec-cap").size)
        ExecutionStatusBus.reset()
    }

    @Test fun theMapperMergesTurnsBetweenWorkStepsInTimeOrder() {
        val events = listOf(
            ExecutionTraceEvent("e1", 1, 100, ExecutionTraceKind.PLANNING, "Planning task"),
            ExecutionTraceEvent("e1", 2, 300, ExecutionTraceKind.TOOL_COMPLETED, "Tool completed: write_file", machineTag = "tool_call"),
            ExecutionTraceEvent("e1", 3, 500, ExecutionTraceKind.COMPLETED, "done"),
        )
        val trace = ChatExecutionTraceMapper.toAgentTrace(
            executionId = "e1", sessionId = "s", messageId = 1, userInput = "x", events = events, answerText = "ok",
            modelTurns = listOf(record(1, 200), record(2, 400, finish = "STOP")),
        )!!
        assertEquals(listOf(AgentStepType.MODEL_TURN, AgentStepType.TOOL, AgentStepType.MODEL_TURN), trace.steps.map { it.type })
        assertEquals(listOf(0, 1, 2), trace.steps.map { it.stepIndex })
        assertEquals(1, trace.stepCount)
    }

    @Test fun turnsAreNotCountedAgainstTheWorkStepCap() {
        val events = (1..60).map {
            ExecutionTraceEvent("e2", it.toLong(), it * 10L, ExecutionTraceKind.TOOL_COMPLETED, "Tool completed: read_file", machineTag = "tool_call")
        } + ExecutionTraceEvent("e2", 61, 700, ExecutionTraceKind.COMPLETED, "done")
        val trace = ChatExecutionTraceMapper.toAgentTrace(
            executionId = "e2", sessionId = "s", messageId = 1, userInput = "x", events = events, answerText = "ok",
            modelTurns = (1..20).map { record(it, it * 30L) },
        )!!
        assertEquals(20, trace.steps.count { it.type == AgentStepType.MODEL_TURN })
        assertEquals(com.airi.assistant.ai.agent.trace.AgentTraceManager.MAX_STEPS, trace.stepCount)
        assertTrue(trace.truncated)
    }

    @Test fun modelTurnsNeverClearAnUnrecoveredFailure() {
        val events = listOf(
            ExecutionTraceEvent("e3", 1, 100, ExecutionTraceKind.TOOL_FAILED, "Tool failed: run_python", machineTag = "tool_call"),
            ExecutionTraceEvent("e3", 2, 300, ExecutionTraceKind.COMPLETED, "done"),
        )
        val trace = ChatExecutionTraceMapper.toAgentTrace(
            executionId = "e3", sessionId = "s", messageId = 1, userInput = "x", events = events, answerText = "ok",
            modelTurns = listOf(record(1, 200)),
        )!!
        assertFalse(trace.success)
    }

    @Test fun stepRetryLabelNamesTheToolAndItsSubject() {
        val t = traceWith(tool("write_file", 1))
        assertEquals("write_file (a.py)", stepRetryLabel(buildTimeline(t).single()))
    }
}
