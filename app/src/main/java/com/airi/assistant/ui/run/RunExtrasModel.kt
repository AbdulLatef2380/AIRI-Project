package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.ModelTurnSteps
import kotlin.math.ceil

/*
 * Part 5, batch 4: pure models for §3.4 (model rows), §3.6 (approval card), §3.8 (footer),
 * §1 (chat run card) and §4 (workspace sheet). The composables hold no logic of their own.
 */

// ── §3.4 Model rows ────────────────────────────────────────────────────────────────────────

/**
 * What the trace records about the model(s) that served the run. A run that recorded its model
 * calls (Part 5 §3.4) yields one [TurnDetail] row per call, with a [Switch] row wherever the
 * serving provider or model changed. An older run, or one without recorded calls, yields the
 * run-level [Turn] row (one resolved target plus an optional fallback).
 *
 * [timestampMs] places a row among the timeline rows; 0 sorts it first (run-level rows).
 */
sealed interface ModelRunItem {
    val timestampMs: Long

    data class Turn(
        val provider: String?,
        val model: String?,
        val outputTokens: Int?,
        val firstTokenMs: Long?,
        override val timestampMs: Long = 0L,
    ) : ModelRunItem

    data class Switch(
        val from: String,
        val to: String,
        val reason: String?,
        override val timestampMs: Long = 0L,
    ) : ModelRunItem

    /** One recorded model call. Token counts are estimates. */
    data class TurnDetail(
        val turn: Int?,
        val provider: String?,
        val model: String?,
        /** True when the loop forced a tool call on this turn (`toolChoice = required`). */
        val toolRequired: Boolean,
        val finish: TurnFinish,
        val promptTokens: Int?,
        val outputTokens: Int?,
        val durationMs: Long?,
        override val timestampMs: Long = 0L,
    ) : ModelRunItem
}

/** Normalized finish reasons the UI knows how to word; anything else is [UNKNOWN] and not shown. */
enum class TurnFinish {
    STOP, TOOL_CALLS, LENGTH_LIMIT, CONTENT_FILTER, CANCELLED, ERROR, CONNECTION_LOST, UNKNOWN;

    companion object {
        fun parse(raw: String?): TurnFinish =
            values().firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

private fun target(provider: String?, model: String?): String? =
    listOfNotNull(provider?.takeIf { it.isNotBlank() }, model?.takeIf { it.isNotBlank() })
        .joinToString(" / ").takeIf { it.isNotEmpty() }

fun buildModelItems(trace: AgentTrace): List<ModelRunItem> {
    val recorded = trace.steps.mapNotNull { step -> ModelTurnSteps.view(step)?.let { step to it } }
    return if (recorded.isNotEmpty()) perTurnItems(recorded) else runLevelItems(trace)
}

private fun perTurnItems(
    recorded: List<Pair<com.airi.assistant.ai.agent.trace.AgentStep, com.airi.assistant.ai.agent.trace.ModelTurnView>>,
): List<ModelRunItem> {
    val items = mutableListOf<ModelRunItem>()
    var served: String? = null
    recorded.forEach { (step, view) ->
        val current = target(view.provider, view.model)
        // The first turn is compared with what was asked for; later ones with the previous turn.
        val before = served ?: target(view.requestedProvider, view.requestedModel)
        if (current != null && before != null && before != current) {
            items += ModelRunItem.Switch(from = before, to = current, reason = view.fallbackReason, timestampMs = step.timestampMs)
        }
        if (current != null) served = current
        items += ModelRunItem.TurnDetail(
            turn = view.turn,
            provider = view.provider,
            model = view.model,
            toolRequired = view.toolChoice.equals(com.airi.assistant.ai.agent.trace.ModelTurnRecord.TOOL_CHOICE_REQUIRED, ignoreCase = true),
            finish = TurnFinish.parse(view.finishReason),
            promptTokens = view.promptTokens?.takeIf { it > 0 },
            outputTokens = view.outputTokens?.takeIf { it > 0 },
            durationMs = view.durationMs,
            timestampMs = step.timestampMs,
        )
    }
    return items
}

private fun runLevelItems(trace: AgentTrace): List<ModelRunItem> {
    val provider = (trace.resolvedProvider ?: trace.provider)?.takeIf { it.isNotBlank() }
    val model = (trace.resolvedModel ?: trace.model)?.takeIf { it.isNotBlank() }
    val resolved = target(provider, model) ?: return emptyList()
    val requested = target(
        (trace.requestedProvider ?: trace.provider)?.takeIf { it.isNotBlank() },
        (trace.requestedModel ?: trace.model)?.takeIf { it.isNotBlank() },
    )
    val items = mutableListOf<ModelRunItem>()
    if (requested != null && requested != resolved) {
        items += ModelRunItem.Switch(from = requested, to = resolved, reason = trace.fallbackReason?.takeIf { it.isNotBlank() })
    }
    items += ModelRunItem.Turn(
        provider = provider,
        model = model,
        outputTokens = trace.estimatedOutputTokens?.takeIf { it > 0 },
        firstTokenMs = trace.firstVisibleTokenLatencyMs?.takeIf { it > 0 },
    )
    return items
}

// ── §3.6 Approval card ─────────────────────────────────────────────────────────────────────

const val APPROVAL_TIMEOUT_MS = 30_000L

enum class ApprovalChoice { ALLOW_ONCE, ALLOW_THIS_RUN, DENY }

data class ApprovalCardModel(
    val actionName: String,
    val description: String,
    val destructive: Boolean,
    val requestedAtMs: Long,
    val timeoutMs: Long = APPROVAL_TIMEOUT_MS,
) {
    /**
     * "Allow for this run" is offered only for non-destructive actions: a destructive action is
     * always asked about again, so one tap can never authorize a whole run of destructive steps.
     */
    val choices: List<ApprovalChoice>
        get() = if (destructive) listOf(ApprovalChoice.ALLOW_ONCE, ApprovalChoice.DENY)
        else listOf(ApprovalChoice.ALLOW_ONCE, ApprovalChoice.ALLOW_THIS_RUN, ApprovalChoice.DENY)

    fun remainingSeconds(nowMs: Long): Int =
        ceil((requestedAtMs + timeoutMs - nowMs) / 1000.0).toInt().coerceIn(0, (timeoutMs / 1000L).toInt())
}

/**
 * The approval belongs to this run when the execution ids match. If either id is unknown the
 * card is shown only for a live run, never for a finished one.
 */
fun approvalAppliesTo(trace: AgentTrace, approvalExecutionId: String): Boolean = when {
    !trace.inProgress -> false
    trace.executionId.isNotBlank() && approvalExecutionId.isNotBlank() -> trace.executionId == approvalExecutionId
    else -> true
}

// ── §3.8 Footer ────────────────────────────────────────────────────────────────────────────

enum class FollowUp { EXPLAIN_STEPS, RETRY_FAILED, VERIFY_AGAIN }

data class RunFooter(val followUps: List<FollowUp>, val canRerun: Boolean)

fun buildFooter(trace: AgentTrace): RunFooter {
    if (trace.inProgress) return RunFooter(emptyList(), canRerun = false)
    val followUps = if (trace.success) {
        buildList {
            add(FollowUp.EXPLAIN_STEPS)
            if (buildPlan(trace) != null) add(FollowUp.VERIFY_AGAIN)
        }
    } else {
        listOf(FollowUp.RETRY_FAILED, FollowUp.EXPLAIN_STEPS)
    }
    return RunFooter(followUps, canRerun = trace.originalInput.isNotBlank())
}

// ── §1 Chat run card ───────────────────────────────────────────────────────────────────────

enum class RunCardStatus { RUNNING, COMPLETED, FAILED }

data class RunCardModel(
    val runId: String,
    val title: String,
    val status: RunCardStatus,
    /** Real timeline rows, so the number always matches what the run screen lists. */
    val stepCount: Int,
    val startedAtMs: Long,
    val elapsedMs: Long?,
)

/** Null for a conversation without tool steps: such a chat shows no run card. */
fun buildRunCardModel(trace: AgentTrace): RunCardModel? {
    val steps = buildTimeline(trace).size
    if (steps == 0) return null
    return RunCardModel(
        runId = trace.id,
        title = trace.originalInput.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty().take(120),
        status = when {
            trace.inProgress -> RunCardStatus.RUNNING
            trace.success -> RunCardStatus.COMPLETED
            else -> RunCardStatus.FAILED
        },
        stepCount = steps,
        startedAtMs = trace.timestamp,
        elapsedMs = if (trace.inProgress) null else trace.steps.sumOf { it.durationMs.coerceAtLeast(0L) }.takeIf { it > 0 },
    )
}

// ── §4 Workspace sheet ─────────────────────────────────────────────────────────────────────

data class WorkspaceFile(val rowId: String, val name: String, val written: Boolean, val failed: Boolean)

data class TerminalEntry(
    val rowId: String,
    val command: String,
    val output: String,
    val failed: Boolean,
    val durationMs: Long?,
)

data class WorkspaceSheetModel(val files: List<WorkspaceFile>, val terminal: List<TerminalEntry>) {
    val isEmpty: Boolean get() = files.isEmpty() && terminal.isEmpty()
}

private val WRITE_HINTS = listOf("write", "create", "edit", "save", "append")

/**
 * Files and commands are read from the same timeline rows the run screen shows. A file appears
 * once, at its latest state; a failed write is marked and never counted as written.
 */
fun buildWorkspaceSheet(rows: List<StepRow>): WorkspaceSheetModel {
    val files = LinkedHashMap<String, WorkspaceFile>()
    val terminal = mutableListOf<TerminalEntry>()
    for (row in rows) {
        val subject = row.subject ?: continue
        when (row.category) {
            "FILE" -> {
                val failed = row.status == StepStatus.FAILED
                val isWrite = WRITE_HINTS.any { row.name.lowercase().contains(it) }
                val previous = files[subject]
                files[subject] = WorkspaceFile(
                    rowId = row.id,
                    name = subject,
                    written = !failed && (isWrite || previous?.written == true),
                    failed = failed,
                )
            }
            "COMMAND" -> terminal += TerminalEntry(
                rowId = row.id,
                command = subject,
                output = (row.errorMessage ?: row.outputPreview),
                failed = row.status == StepStatus.FAILED,
                durationMs = row.durationMs,
            )
        }
    }
    return WorkspaceSheetModel(files.values.toList(), terminal)
}
