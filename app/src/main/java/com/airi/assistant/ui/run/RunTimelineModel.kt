package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.data.run.AgentRunMapper

/** Part 5 §3: pure presentation model of the run screen; the composables hold no logic. */
enum class PlanState { NOT_STARTED, RUNNING, DONE, FAILED }

data class PlanItem(val text: String, val state: PlanState)

data class PlanProgress(val items: List<PlanItem>) {
    val done: Int get() = items.count { it.state == PlanState.DONE }
    val total: Int get() = items.size
}

enum class StepStatus { RUNNING, DONE, FAILED }

data class StepRow(
    /** Stable LazyColumn key. */
    val id: String,
    val category: String,
    /** Raw tool name; the UI maps category + subject to a localized title. */
    val name: String,
    /** Human subject such as a file name, command or query; already redacted upstream. */
    val subject: String?,
    val status: StepStatus,
    val durationMs: Long?,
    val depth: Int,
    val inputPreview: List<Pair<String, String>>,
    val outputPreview: String,
    val errorMessage: String?,
    /** Machine code the UI translates into a message and a suggested action. */
    val errorCode: String?,
    /** Event time of the step; places it among the model rows in [buildFeed]. */
    val timestampMs: Long = 0L,
)

private val SUBJECT_KEYS = listOf("path", "file", "filename", "file_name", "command", "cmd", "query", "url", "name")
private val ISSUE_SPLIT = Regex("[\\r\\n]+|\\s*[;•]\\s*")
private val BULLET = Regex("^\\s*(?:[-*•]|\\d+[.)])\\s*")
private val RATIO = Regex("(\\d+)\\s*/\\s*(\\d+)")

internal fun subjectOf(step: AgentStep): String? =
    SUBJECT_KEYS.firstNotNullOfOrNull { key ->
        step.inputParams.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
    }?.trim()?.takeIf { it.isNotEmpty() }?.substringAfterLast('/')?.take(80)

/**
 * Requirements come from the PLAN step. Progress is read from the VERIFY step's "n/m" report when
 * it exists; otherwise a successful run marks all done and an unfinished one marks none.
 */
fun buildPlan(trace: AgentTrace): PlanProgress? {
    val planStep = trace.steps.firstOrNull { it.type == AgentStepType.PLAN } ?: return null
    val texts = planStep.outputSummary.split(ISSUE_SPLIT)
        .map { it.replace(BULLET, "").trim() }
        .filter { it.length >= 2 }
        .distinct()
    if (texts.isEmpty()) return null
    val verify = trace.steps.lastOrNull { it.type == AgentStepType.VERIFY }
    val reported = verify?.let { RATIO.find(it.outputSummary) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }
    val doneCount = when {
        reported != null && reported.second == texts.size -> reported.first.coerceIn(0, texts.size)
        !trace.inProgress && trace.success -> texts.size
        else -> 0
    }
    val items = texts.mapIndexed { i, text ->
        val state = when {
            i < doneCount -> PlanState.DONE
            trace.inProgress && i == doneCount -> PlanState.RUNNING
            !trace.inProgress && !trace.success && i == doneCount -> PlanState.FAILED
            else -> PlanState.NOT_STARTED
        }
        PlanItem(texts[i], state)
    }
    return PlanProgress(items)
}

/** Timeline rows: tool and task steps only. Intent/plan/verify decisions live in [buildDecisions]. */
fun buildTimeline(trace: AgentTrace): List<StepRow> {
    val rows = trace.steps.withIndex().filter { (_, s) ->
        s.type == AgentStepType.TOOL || s.type == AgentStepType.SKILL || s.type == AgentStepType.TASK_STEP
    }
    val names = rows.map { it.value.name }.toSet()
    // "Still running" is the newest work row; model turns may follow it in the step list.
    val lastRowIndex = rows.lastOrNull()?.index
    return rows.map { (index, step) ->
        val failed = !step.success || step.error != null
        StepRow(
            id = "${trace.id}:${step.stepIndex}:$index",
            category = AgentRunMapper.category(step),
            name = step.name,
            subject = subjectOf(step),
            status = when {
                failed -> StepStatus.FAILED
                trace.inProgress && index == lastRowIndex -> StepStatus.RUNNING
                else -> StepStatus.DONE
            },
            durationMs = step.durationMs.takeIf { it > 0 },
            depth = if (step.parentId != null && step.parentId in names && step.parentId != step.name) 1 else 0,
            inputPreview = step.inputParams.entries.map { it.key to it.value.take(200) },
            outputPreview = step.outputSummary.take(600),
            errorMessage = step.error?.take(600),
            errorCode = if (failed) step.error?.let { "tool_error" } ?: "tool_failed" else null,
            timestampMs = step.timestampMs,
        )
    }
}

/** Intent and verification decisions, shown in the collapsed "Decisions" group. */
fun buildDecisions(trace: AgentTrace): List<AgentStep> =
    trace.steps.filter { it.type == AgentStepType.INTENT || it.type == AgentStepType.VERIFY }

enum class SuggestedAction { RETRY, SWITCH_MODEL, GRANT_PERMISSION, NONE }

/** Maps a failure to the one action worth offering; the UI supplies the localized label. */
fun suggestedActionFor(row: StepRow): SuggestedAction {
    if (row.status != StepStatus.FAILED) return SuggestedAction.NONE
    val text = (row.errorMessage.orEmpty() + " " + row.errorCode.orEmpty()).lowercase()
    return when {
        listOf("permission", "denied", "not allowed", "unauthorized", "approval").any(text::contains) -> SuggestedAction.GRANT_PERMISSION
        listOf("rate limit", "429", "quota", "overloaded", "model", "provider", "context length").any(text::contains) -> SuggestedAction.SWITCH_MODEL
        else -> SuggestedAction.RETRY
    }
}

/** One entry of the run screen's main list: a work row or a model row, in time order. */
sealed interface FeedItem {
    val key: String

    data class Step(val row: StepRow) : FeedItem {
        override val key: String get() = row.id
    }

    data class Model(val index: Int, val item: ModelRunItem) : FeedItem {
        override val key: String get() = "model:$index"
    }
}

/**
 * Interleaves model rows with work rows by event time. The sort is stable and model rows are listed
 * first, so a switch row stays before its turn and a turn that ends in the same millisecond as its
 * tool call still precedes it. Rows without a time (timestamp 0, such as the run-level model row of
 * an older run) lead the list.
 */
fun buildFeed(rows: List<StepRow>, modelItems: List<ModelRunItem>): List<FeedItem> {
    val all = modelItems.mapIndexed { i, item -> FeedItem.Model(i, item) as FeedItem } +
        rows.map { FeedItem.Step(it) }
    return all.sortedBy { feed ->
        when (feed) {
            is FeedItem.Model -> feed.item.timestampMs
            is FeedItem.Step -> feed.row.timestampMs
        }
    }
}

/** Short identification of a step for a retry request: the tool name plus its subject when known. */
fun stepRetryLabel(row: StepRow): String =
    row.subject?.let { "${row.name} ($it)" } ?: row.name
