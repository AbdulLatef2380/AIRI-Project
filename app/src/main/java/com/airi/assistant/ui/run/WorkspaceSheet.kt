package com.airi.assistant.ui.run

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.agent.loop.tool.AgentFileHistory
import com.airi.assistant.agent.loop.tool.AgentFileWriter
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.run.code.CodeTab
import com.airi.assistant.ui.run.code.LineDiff
import com.airi.assistant.ui.run.code.buildFileHistory
import com.airi.assistant.ui.run.code.buildReplayFrames
import com.airi.assistant.ui.run.code.clampFrameIndex
import com.airi.assistant.ui.run.code.frameDiff
import com.airi.assistant.ui.run.code.liveFrameIndex
import com.airi.assistant.ui.theme.AiriTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.roundToInt

private enum class WorkspaceTab { FILES, TERMINAL, REPLAY }

/**
 * Part 5 §4: the "Agent workspace" bottom sheet. Files (tap one to open the read-only editor with
 * Current / Original / Diff), Terminal, and, when the run wrote files, a Replay bar that steps
 * through the recorded writes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceSheet(model: WorkspaceSheetModel, trace: AgentTrace, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val filesDir = context.filesDir
    // Reloaded when the run adds a step, so a live run's new writes appear without reopening.
    val snapshots by produceState(emptyList<AgentFileHistory.Snapshot>(), trace.executionId, trace.steps.size) {
        value = withContext(Dispatchers.IO) {
            runCatching { AgentFileHistory.load(File(filesDir, AgentFileHistory.DIRECTORY_NAME), trace.executionId) }
                .getOrDefault(emptyList())
        }
    }
    val tabs = remember(snapshots.isNotEmpty()) {
        buildList {
            add(WorkspaceTab.FILES)
            add(WorkspaceTab.TERMINAL)
            if (snapshots.isNotEmpty()) add(WorkspaceTab.REPLAY)
        }
    }
    var selected by remember { mutableStateOf(WorkspaceTab.FILES) }
    val active = if (selected in tabs) selected else WorkspaceTab.FILES
    var openFile by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AiriTheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Text(
                stringResource(R.string.run_workspace_title),
                color = AiriTheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PrimaryTabRow(selectedTabIndex = tabs.indexOf(active), containerColor = Color.Transparent) {
                tabs.forEach { tab ->
                    val label = when (tab) {
                        WorkspaceTab.FILES -> "${stringResource(R.string.run_workspace_files)} (${model.files.size})"
                        WorkspaceTab.TERMINAL -> "${stringResource(R.string.run_workspace_terminal)} (${model.terminal.size})"
                        WorkspaceTab.REPLAY -> "${stringResource(R.string.run_workspace_replay)} (${snapshots.size})"
                    }
                    Tab(
                        selected = tab == active,
                        onClick = { selected = tab; openFile = null },
                        text = { Text(label, fontSize = 13.sp) },
                    )
                }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (active) {
                    WorkspaceTab.FILES -> {
                        val name = openFile
                        if (name != null) {
                            FileViewer(name, snapshots, filesDir, onBack = { openFile = null })
                        } else {
                            FileList(model, onOpen = { openFile = it })
                        }
                    }
                    WorkspaceTab.TERMINAL -> TerminalList(model)
                    WorkspaceTab.REPLAY -> ReplayPane(snapshots)
                }
            }
        }
    }
}

@Composable
private fun FileList(model: WorkspaceSheetModel, onOpen: (String) -> Unit) {
    if (model.files.isEmpty()) StatusText(R.string.run_workspace_empty)
    model.files.forEach { file ->
        Row(
            Modifier.fillMaxWidth().clickable { onOpen(file.name) }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                file.name, color = AiriTheme.onBackground, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            val (label, color) = when {
                file.failed -> R.string.run_workspace_failed to ErrColor
                file.written -> R.string.run_workspace_written to OkColor
                else -> R.string.run_workspace_read to AiriTheme.onSurfaceVariant
            }
            Text(stringResource(label), color = color, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TerminalList(model: WorkspaceSheetModel) {
    if (model.terminal.isEmpty()) StatusText(R.string.run_workspace_empty)
    model.terminal.forEach { entry ->
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$ ${entry.command}", color = AiriTheme.onBackground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text(
                    entry.output.ifBlank { stringResource(R.string.run_workspace_no_output) },
                    color = if (entry.failed) ErrColor else AiriTheme.onSurfaceVariant,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 12, overflow = TextOverflow.Ellipsis,
                )
                entry.durationMs?.let {
                    Text(DateUtils.formatElapsedTime((it / 1000L).coerceAtLeast(1L)), color = AiriTheme.outline, fontSize = 10.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileViewer(name: String, snapshots: List<AgentFileHistory.Snapshot>, filesDir: File, onBack: () -> Unit) {
    val history = remember(name, snapshots) { buildFileHistory(name, snapshots) }
    // Without a recorded write the best available "current" is the file itself, read off the main thread.
    val disk by produceState(false to (null as String?), name, history) {
        value = true to when {
            history != null -> history.current
            else -> withContext(Dispatchers.IO) {
                AgentFileHistory.readExisting(File(filesDir, AgentFileWriter.DIRECTORY_NAME), name)
            }
        }
    }
    var tab by remember(name) { mutableStateOf(CodeTab.CURRENT) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
            }
            Text(
                name, color = AiriTheme.onBackground, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CodeTab.entries.forEach { t ->
                FilterChip(
                    selected = tab == t,
                    onClick = { tab = t },
                    label = {
                        Text(
                            stringResource(
                                when (t) {
                                    CodeTab.CURRENT -> R.string.run_code_current
                                    CodeTab.ORIGINAL -> R.string.run_code_original
                                    CodeTab.DIFF -> R.string.run_code_diff
                                }
                            ),
                            fontSize = 12.sp,
                        )
                    },
                )
            }
        }
        when (tab) {
            CodeTab.CURRENT -> {
                val (loaded, text) = disk
                when {
                    !loaded -> Unit
                    text == null -> StatusText(R.string.run_code_read_failed)
                    else -> HighlightedCode(text, name)
                }
            }
            CodeTab.ORIGINAL -> when {
                history == null -> StatusText(R.string.run_code_no_snapshot)
                history.original == null -> StatusText(R.string.run_code_created)
                else -> HighlightedCode(history.original, name)
            }
            CodeTab.DIFF -> if (history == null) {
                StatusText(R.string.run_code_no_snapshot)
            } else {
                val diff = remember(history) { LineDiff.view(history.original.orEmpty(), history.current, maxRows = DIFF_UI_MAX_ROWS) }
                CodeDiffViewer(diff)
            }
        }
    }
}

/** Steps through the recorded writes. Sitting on the newest frame is "live"; scrubbing back leaves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReplayPane(snapshots: List<AgentFileHistory.Snapshot>) {
    val context = LocalContext.current
    val frames = remember(snapshots) { buildReplayFrames(snapshots) }
    if (frames.isEmpty()) {
        StatusText(R.string.run_workspace_empty)
        return
    }
    var index by remember { mutableIntStateOf(liveFrameIndex(frames)) }
    var followLive by remember { mutableStateOf(true) }
    // A live run adds frames: stay on the newest one unless the person scrubbed back.
    LaunchedEffect(frames.size) { if (followLive) index = liveFrameIndex(frames) }
    val position = clampFrameIndex(index, frames)
    val frame = frames[position]
    var showContent by remember { mutableStateOf(false) }

    val time = if (frame.timestampMs > 0L) DateFormat.getTimeFormat(context).format(Date(frame.timestampMs)) else ""
    Text(
        stringResource(R.string.run_replay_frame, frame.index + 1, frame.total, frame.fileName),
        color = AiriTheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
    if (time.isNotEmpty()) Text(time, color = AiriTheme.outline, fontSize = 11.sp)
    if (frames.size > 1) {
        Slider(
            value = position.toFloat(),
            onValueChange = {
                index = it.roundToInt()
                followLive = index >= liveFrameIndex(frames)
            },
            valueRange = 0f..liveFrameIndex(frames).toFloat(),
            steps = (frames.size - 2).coerceAtLeast(0),
        )
    }
    if (position != liveFrameIndex(frames)) {
        TextButton(onClick = { index = liveFrameIndex(frames); followLive = true }) {
            Text(stringResource(R.string.run_jump_live), fontSize = 13.sp)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !showContent, onClick = { showContent = false }, label = { Text(stringResource(R.string.run_code_diff), fontSize = 12.sp) })
        FilterChip(selected = showContent, onClick = { showContent = true }, label = { Text(stringResource(R.string.run_code_content), fontSize = 12.sp) })
    }
    if (showContent) HighlightedCode(frame.after, frame.fileName)
    else CodeDiffViewer(remember(frame) { frameDiff(frame, maxRows = DIFF_UI_MAX_ROWS) })
}
