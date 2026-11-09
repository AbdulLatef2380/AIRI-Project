package com.airi.assistant.ui.run.code

import com.airi.assistant.agent.loop.tool.AgentFileHistory

/**
 * Part 5 §4: pure models behind the Editor tabs (Current / Original / Diff) and the replay bar.
 * They only reorganize [AgentFileHistory.Snapshot]s; nothing here touches storage or Android.
 */
enum class CodeTab { CURRENT, ORIGINAL, DIFF }

/**
 * What one run did to one file. [original] is the content before the run's first write, or null
 * when the run created the file (there was no earlier version). [current] is the content after
 * the run's last write.
 */
data class FileRunHistory(
    val fileName: String,
    val original: String?,
    val current: String,
    val writes: Int,
) {
    val createdInRun: Boolean get() = original == null
}

/** Null when the run recorded no write to [fileName] (it was only read, or the run predates snapshots). */
fun buildFileHistory(fileName: String, snapshots: List<AgentFileHistory.Snapshot>): FileRunHistory? {
    val mine = snapshots.filter { it.fileName == fileName }.sortedBy { it.sequence }
    if (mine.isEmpty()) return null
    return FileRunHistory(
        fileName = fileName,
        original = mine.first().before,
        current = mine.last().after,
        writes = mine.size,
    )
}

/** One step of the replay bar: the state of one file right after one write. */
data class ReplayFrame(
    /** 0-based position and total, so the label reads "step n of m". */
    val index: Int,
    val total: Int,
    val fileName: String,
    val before: String?,
    val after: String,
    val timestampMs: Long,
)

fun buildReplayFrames(snapshots: List<AgentFileHistory.Snapshot>): List<ReplayFrame> {
    val ordered = snapshots.sortedBy { it.sequence }
    return ordered.mapIndexed { i, s ->
        ReplayFrame(i, ordered.size, s.fileName, s.before, s.after, s.timestampMs)
    }
}

/** The "live" position is the newest frame; the "Go to live" button jumps here. */
fun liveFrameIndex(frames: List<ReplayFrame>): Int = frames.lastIndex.coerceAtLeast(0)

fun clampFrameIndex(index: Int, frames: List<ReplayFrame>): Int =
    if (frames.isEmpty()) 0 else index.coerceIn(0, frames.lastIndex)

/** Diff of a single frame: what that one write changed. A created file diffs against nothing. */
fun frameDiff(frame: ReplayFrame, maxRows: Int = LineDiff.MAX_ROWS): DiffView =
    LineDiff.view(frame.before.orEmpty(), frame.after, maxRows = maxRows)
