package com.airi.assistant.ui.screens

import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure presentation model for the agent-run list (Part 5, section 2).
 * Everything here is derived from the single trace store; no UI strings live here so the
 * mapping stays unit-testable and the screen resolves every label from strings.xml.
 */
internal enum class RunStatus { RUNNING, COMPLETED, FAILED }

internal enum class RunFilter { ALL, RUNNING, FAILED, HAS_FILES }

internal data class RunCounters(val files: Int, val commands: Int, val tools: Int) {
    val total: Int get() = files + commands + tools
}

internal enum class RunDayBucket { TODAY, YESTERDAY, OTHER }

internal data class RunDayGroup(
    val dayStartMs: Long,
    val bucket: RunDayBucket,
    val runs: List<AgentTrace>,
)

internal fun AgentTrace.runStatus(): RunStatus = when {
    inProgress -> RunStatus.RUNNING
    success -> RunStatus.COMPLETED
    else -> RunStatus.FAILED
}

/** Total measured tool time. Zero when no step recorded a duration (legacy traces). */
internal fun AgentTrace.runDurationMs(): Long = steps.sumOf { it.durationMs.coerceAtLeast(0L) }

private val FILE_HINTS = listOf("file", "write", "read_", "edit", "document", "pdf", "docx", "xlsx", "csv")
private val COMMAND_HINTS = listOf("command", "shell", "terminal", "bash", "exec", "run_", "python", "compile")

/** Classifies tool steps into files / commands / other tools; non-tool steps are not counted. */
internal fun AgentTrace.runCounters(): RunCounters {
    var files = 0
    var commands = 0
    var tools = 0
    steps.forEach { step ->
        if (step.type != AgentStepType.TOOL && step.type != AgentStepType.SKILL) return@forEach
        val n = step.name.lowercase()
        when {
            FILE_HINTS.any(n::contains) -> files++
            COMMAND_HINTS.any(n::contains) -> commands++
            else -> tools++
        }
    }
    return RunCounters(files, commands, tools)
}

/** One-line preview of the final result (markdown markers and line breaks flattened). */
internal fun AgentTrace.resultPreview(maxChars: Int = 140): String =
    finalResult
        .replace(Regex("[`*_#>]+"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(maxChars)

internal fun AgentTrace.matchesQuery(query: String): Boolean {
    val needle = query.trim()
    if (needle.isEmpty()) return true
    return originalInput.contains(needle, ignoreCase = true) ||
        finalResult.contains(needle, ignoreCase = true) ||
        model.orEmpty().contains(needle, ignoreCase = true) ||
        provider.orEmpty().contains(needle, ignoreCase = true)
}

internal fun AgentTrace.matchesFilter(filter: RunFilter): Boolean = when (filter) {
    RunFilter.ALL -> true
    RunFilter.RUNNING -> inProgress
    RunFilter.FAILED -> !inProgress && runStatus() == RunStatus.FAILED
    RunFilter.HAS_FILES -> runCounters().files > 0
}

/** Title is the first non-blank line of the request. */
internal fun AgentTrace.runTitle(): String =
    originalInput.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty()

internal fun dayStartMs(timestampMs: Long, zone: TimeZone = TimeZone.getDefault()): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = timestampMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Filters, sorts and groups runs by local calendar day. A running run is pinned to the top of
 * the first group regardless of its start time, per the spec ("pinned at the top of the list").
 */
internal fun buildRunList(
    traces: List<AgentTrace>,
    query: String,
    filter: RunFilter,
    nowMs: Long = System.currentTimeMillis(),
    zone: TimeZone = TimeZone.getDefault(),
): List<RunDayGroup> {
    val visible = traces
        .filter { it.matchesFilter(filter) && it.matchesQuery(query) }
        .sortedWith(compareByDescending<AgentTrace> { it.inProgress }.thenByDescending { it.timestamp })
    if (visible.isEmpty()) return emptyList()
    val today = dayStartMs(nowMs, zone)
    val yesterday = dayStartMs(today - 1L, zone)
    val pinned = visible.filter { it.inProgress }
    val rest = visible.filterNot { it.inProgress }
    val groups = mutableListOf<RunDayGroup>()
    if (pinned.isNotEmpty()) groups += RunDayGroup(today, RunDayBucket.TODAY, pinned)
    rest.groupBy { dayStartMs(it.timestamp, zone) }
        .toSortedMap(compareByDescending<Long> { it })
        .forEach { (day, runs) ->
            val bucket = when (day) {
                today -> RunDayBucket.TODAY
                yesterday -> RunDayBucket.YESTERDAY
                else -> RunDayBucket.OTHER
            }
            // Merge into the pinned "today" group so the day header appears once.
            val last = groups.lastOrNull()
            if (last != null && last.dayStartMs == day && last.bucket == bucket) {
                groups[groups.lastIndex] = last.copy(runs = last.runs + runs)
            } else {
                groups += RunDayGroup(day, bucket, runs)
            }
        }
    return groups
}
