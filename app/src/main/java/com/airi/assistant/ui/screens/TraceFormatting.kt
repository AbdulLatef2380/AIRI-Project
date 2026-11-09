package com.airi.assistant.ui.screens

import android.text.format.DateFormat
import com.airi.assistant.ai.agent.trace.AgentTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-aware date formatting for the agent-log screens. The skeleton is resolved per
 * locale, so Arabic/Spanish/Chinese get native month names and ordering instead of the
 * hard-coded English "MMM d, HH:mm" pattern.
 */
internal fun formatTraceDate(timestampMs: Long, withSeconds: Boolean = false): String {
    val locale = Locale.getDefault()
    val skeleton = if (withSeconds) "MMMdHHmmss" else "MMMdHHmm"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(timestampMs))
}

/** Plain-text export of a trace for the clipboard. Content is already redacted by the trace manager. */
internal fun AgentTrace.toClipboardText(): String = buildString {
    appendLine(originalInput)
    appendLine("${formatTraceDate(timestamp, withSeconds = true)} | $successCount/$stepCount")
    steps.forEachIndexed { index, step ->
        appendLine()
        appendLine("${index + 1}. ${step.displayName} (${step.typeLabel})")
        step.inputParams.forEach { (k, v) -> appendLine("   $k: $v") }
        val detail = step.error ?: step.outputSummary
        if (detail.isNotBlank()) appendLine("   $detail")
    }
    if (finalResult.isNotBlank()) {
        appendLine()
        appendLine(finalResult)
    }
}.trimEnd()
