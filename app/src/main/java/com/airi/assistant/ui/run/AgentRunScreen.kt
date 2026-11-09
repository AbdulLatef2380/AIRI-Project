package com.airi.assistant.ui.run

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.R
import com.airi.assistant.agent.loop.tool.AgentFileCards
import com.airi.assistant.agent.loop.tool.AgentFileWriter
import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ui.components.AgentFileCards as AgentFileCardsRow
import com.airi.assistant.ui.screens.formatTraceDate
import com.airi.assistant.ui.screens.toClipboardText
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.util.MarkdownText
import kotlinx.coroutines.delay

internal val OkColor = Color(0xFF2E9E6B)
internal val ErrColor = Color(0xFFFF5252)
internal val WarnColor = Color(0xFFE59E0B)
private const val SHARE_MAX_CHARS = 60_000

/**
 * Everything the run screen can ask its host to do or show beyond what the trace itself holds.
 * All defaults are inert, so existing callers keep compiling and behaving as before.
 */
data class RunScreenActions(
    /** Pending approval for this run, already matched to it by the host (see [approvalAppliesTo]). */
    val approval: ApprovalCardModel? = null,
    val onApproval: (ApprovalChoice) -> Unit = {},
    /** 1 liked, -1 disliked, 0 none; only meaningful when [onRate] is set. */
    val feedback: Int = 0,
    /** Null when the run has no chat message to rate, which hides the rating buttons. */
    val onRate: ((liked: Boolean) -> Unit)? = null,
    val onRerun: (AgentTrace) -> Unit = {},
    /** The localized follow-up text; the host puts it into the composer for the user to send. */
    val onFollowUp: (String) -> Unit = {},
    val onSwitchModel: () -> Unit = {},
    val onGrantPermission: () -> Unit = {},
    /** Stops the live run; null (hides the button) when this run is not the one generating now. */
    val onStop: (() -> Unit)? = null,
    /** Saved reason behind a thumbs-down, and the setter; a null setter hides the reason chips. */
    val reason: RatingReason? = null,
    val onReason: ((RatingReason) -> Unit)? = null,
)

/**
 * Part 5 §3. Stateless apart from scroll/expand UI state: everything shown comes from
 * [AgentRunScreenState], which the [AgentRunViewModel] derives from the single trace store.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRunScreen(
    viewModel: AgentRunViewModel,
    runId: String,
    onBack: () -> Unit,
    onDelete: (AgentTrace) -> Unit,
    onOpenMessage: (sessionId: String, messageId: Long) -> Unit,
    /** Developer-mode only (Part 5 §6 privacy): null hides the raw "full details" entry. */
    onOpenDiagnostics: ((AgentTrace) -> Unit)? = null,
    actions: RunScreenActions = RunScreenActions(),
) {
    LaunchedEffect(runId) { viewModel.open(runId) }
    // The approval gate is the live source for the "waiting" state (Part 5 §5).
    LaunchedEffect(actions.approval != null) { viewModel.setWaitingApproval(actions.approval != null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        AgentRunScreenState.Loading -> CenteredStatus(onBack) { CircularProgressIndicator(color = CosmicAccent) }
        AgentRunScreenState.Missing -> CenteredStatus(onBack) { StatusText(R.string.run_state_missing) }
        AgentRunScreenState.Offline -> CenteredStatus(onBack) { StatusText(R.string.run_state_offline) }
        is AgentRunScreenState.Running -> RunContent(s.trace, false, false, onBack, onDelete, onOpenMessage, onOpenDiagnostics, actions)
        is AgentRunScreenState.WaitingApproval -> RunContent(s.trace, true, false, onBack, onDelete, onOpenMessage, onOpenDiagnostics, actions)
        is AgentRunScreenState.Completed -> RunContent(s.trace, false, false, onBack, onDelete, onOpenMessage, onOpenDiagnostics, actions)
        is AgentRunScreenState.Failed -> RunContent(s.trace, false, s.cancelled, onBack, onDelete, onOpenMessage, onOpenDiagnostics, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CenteredStatus(onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {},
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
internal fun StatusText(res: Int) {
    Text(stringResource(res), color = AiriTheme.onSurfaceVariant, fontSize = 15.sp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunContent(
    trace: AgentTrace,
    waitingApproval: Boolean,
    cancelled: Boolean,
    onBack: () -> Unit,
    onDelete: (AgentTrace) -> Unit,
    onOpenMessage: (sessionId: String, messageId: Long) -> Unit,
    onOpenDiagnostics: ((AgentTrace) -> Unit)?,
    actions: RunScreenActions,
) {
    val plan = remember(trace) { buildPlan(trace) }
    val rows = remember(trace) { buildTimeline(trace) }
    val decisions = remember(trace) { buildDecisions(trace) }
    val modelItems = remember(trace) { buildModelItems(trace) }
    val feed = remember(rows, modelItems) { buildFeed(rows, modelItems) }
    val footer = remember(trace) { buildFooter(trace) }
    val workspace = remember(rows) { buildWorkspaceSheet(rows) }
    var showWorkspace by remember { mutableStateOf(false) }
    val approval = actions.approval
    val listState = rememberLazyListState()
    var follow by remember(trace.id) { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    val running = trace.inProgress

    // Following means "pinned to the newest row". It is recomputed only when a user scroll ends.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) follow = !listState.canScrollForward
    }
    val showResult = trace.finalResult.isNotBlank() || !running
    val showBanner = waitingApproval || cancelled
    val itemCount = (if (showBanner) 1 else 0) + (if (plan != null) 1 else 0) + modelItems.size + rows.size +
        (if (approval != null) 1 else 0) + (if (decisions.isNotEmpty()) 1 else 0) +
        (if (showResult) 1 else 0) + (if (footer.canRerun || footer.followUps.isNotEmpty()) 1 else 0) + 1
    // A pending approval stops the timeline: it scrolls into view and the list stays there.
    LaunchedEffect(feed.size, running, follow, approval != null) {
        if (running && (follow || approval != null)) listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
    }
    val showJump by remember { derivedStateOf { running && !follow } }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            RunHeader(
                trace = trace,
                waitingApproval = waitingApproval,
                cancelled = cancelled,
                onBack = onBack,
                onDelete = { confirmDelete = true },
                onOpenDiagnostics = onOpenDiagnostics?.let { open -> { open(trace) } },
                onOpenWorkspace = if (workspace.isEmpty) null else ({ showWorkspace = true }),
                onStop = if (running) actions.onStop else null,
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = showJump) {
                ExtendedFloatingActionButton(
                    onClick = { follow = true },
                    icon = { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null) },
                    text = { Text(stringResource(R.string.run_jump_live)) },
                )
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (showBanner) item(key = "banner") {
                StatusBanner(waitingApproval, canRerun = footer.canRerun, onRerun = { actions.onRerun(trace) })
            }
            if (plan != null) item(key = "plan") { PlanStrip(plan) }
            items(feed.size, key = { feed[it].key }) { index ->
                when (val entry = feed[index]) {
                    is FeedItem.Model -> ModelItemView(entry.item)
                    is FeedItem.Step -> StepRowView(entry.row, trace, actions)
                }
            }
            if (approval != null) item(key = "approval") { ApprovalCardView(approval, actions.onApproval) }
            if (decisions.isNotEmpty()) item(key = "decisions") { DecisionsGroup(decisions) }
            if (showResult) item(key = "result") {
                ResultSection(trace, onOpenMessage)
            }
            if (footer.canRerun || footer.followUps.isNotEmpty()) item(key = "footer") {
                FooterSection(trace, footer, actions)
            }
            item(key = "end") { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (showWorkspace) {
        WorkspaceSheet(workspace, trace, onDismiss = { showWorkspace = false })
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agent_logs_delete_title)) },
            text = { Text(stringResource(R.string.agent_logs_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(trace) }) {
                    Text(stringResource(R.string.delete), color = ErrColor)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunHeader(
    trace: AgentTrace,
    waitingApproval: Boolean,
    cancelled: Boolean,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    onOpenDiagnostics: (() -> Unit)?,
    onOpenWorkspace: (() -> Unit)?,
    onStop: (() -> Unit)?,
) {
    val context = LocalContext.current
    val copied = stringResource(R.string.agent_logs_copied)
    val chooser = stringResource(R.string.agent_logs_share_chooser)
    var menu by remember { mutableStateOf(false) }
    // Live timer: one tick per second, only while the run is live.
    val elapsedMs by produceState(
        initialValue = (System.currentTimeMillis() - trace.timestamp).coerceAtLeast(0L),
        trace.id, trace.inProgress,
    ) {
        if (!trace.inProgress) {
            value = trace.steps.sumOf { it.durationMs.coerceAtLeast(0L) }
        } else {
            while (true) {
                value = (System.currentTimeMillis() - trace.timestamp).coerceAtLeast(0L)
                delay(1_000)
            }
        }
    }
    val timer = DateUtils.formatElapsedTime((elapsedMs / 1000L).coerceAtLeast(0L))
    val statusText = stringResource(
        when {
            waitingApproval -> R.string.run_state_waiting
            trace.inProgress -> R.string.run_status_running
            cancelled -> R.string.run_state_cancelled
            trace.success -> R.string.run_status_completed
            else -> R.string.run_status_failed
        }
    )
    // Part 5 §5: each state has its own look; a cancelled run is neutral, not an error.
    val statusColor = when {
        waitingApproval -> WarnColor
        trace.inProgress -> CosmicAccent
        trace.success -> OkColor
        cancelled -> AiriTheme.onSurfaceVariant
        else -> ErrColor
    }
    val statusIcon: ImageVector? = when {
        waitingApproval -> Icons.Outlined.PauseCircle
        trace.inProgress -> null
        cancelled -> Icons.Outlined.Block
        trace.success -> Icons.Outlined.CheckCircle
        else -> Icons.Outlined.Error
    }
    val modelLine = listOfNotNull(
        (trace.resolvedModel ?: trace.model)?.takeIf { it.isNotBlank() },
        (trace.resolvedProvider ?: trace.provider)?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.65f)),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
            }
        },
        title = {
            Column {
                Text(
                    trace.originalInput.lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() }.orEmpty(),
                    fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(statusColor.copy(alpha = 0.14f))
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    ) {
                        if (statusIcon != null) {
                            Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(12.dp))
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = statusColor)
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        timer, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.semantics { contentDescription = timer },
                    )
                    if (modelLine.isNotEmpty()) {
                        Text(modelLine, color = AiriTheme.outline, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        actions = {
            if (onStop != null) {
                IconButton(onClick = onStop) {
                    Icon(Icons.Outlined.StopCircle, contentDescription = stringResource(R.string.run_stop_cd), tint = ErrColor)
                }
            }
            if (onOpenWorkspace != null) {
                IconButton(onClick = onOpenWorkspace) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = stringResource(R.string.run_workspace_open_cd), tint = AiriTheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.run_menu_cd), tint = AiriTheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.run_menu_copy)) }, onClick = {
                    menu = false
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("trace", trace.toClipboardText()))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.run_menu_share)) }, onClick = {
                    menu = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, trace.toClipboardText().take(SHARE_MAX_CHARS))
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, chooser)) }
                })
                if (onOpenDiagnostics != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.run_menu_details)) }, onClick = {
                        menu = false; onOpenDiagnostics()
                    })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.run_menu_delete), color = ErrColor) }, onClick = {
                    menu = false; onDelete()
                })
            }
        },
    )
}

@Composable
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(AiriTheme.surface)
            .border(1.dp, AiriTheme.outline, AIRIShapes.md)
            .padding(12.dp),
    ) { content() }
}

/** Part 5 §5: what a paused or stopped run means, and the one thing to do about it. */
@Composable
private fun StatusBanner(waiting: Boolean, canRerun: Boolean, onRerun: () -> Unit) {
    val color = if (waiting) WarnColor else AiriTheme.onSurfaceVariant
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (waiting) Icons.Outlined.PauseCircle else Icons.Outlined.Block, contentDescription = null, tint = color)
            Text(
                stringResource(if (waiting) R.string.run_banner_waiting else R.string.run_banner_cancelled),
                color = AiriTheme.onBackground, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            if (!waiting && canRerun) {
                TextButton(onClick = onRerun) { Text(stringResource(R.string.run_footer_rerun), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun PlanStrip(plan: PlanProgress) {
    var expanded by remember { mutableStateOf(true) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.run_plan_title), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.run_plan_counter, plan.done, plan.total), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) plan.items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (item.state) {
                        PlanState.DONE -> Icon(Icons.Outlined.CheckCircle, null, tint = OkColor, modifier = Modifier.size(16.dp))
                        PlanState.FAILED -> Icon(Icons.Outlined.Error, null, tint = ErrColor, modifier = Modifier.size(16.dp))
                        PlanState.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CosmicAccent)
                        PlanState.NOT_STARTED -> Icon(Icons.Outlined.Circle, null, tint = AiriTheme.outline, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(item.text, color = AiriTheme.onBackground.copy(alpha = 0.9f), fontSize = 13.sp)
                }
            }
        }
    }
}

private fun categoryIcon(category: String): ImageVector = when (category) {
    "FILE" -> Icons.Outlined.Description
    "COMMAND" -> Icons.Outlined.Terminal
    "SEARCH" -> Icons.Outlined.Search
    else -> Icons.Outlined.Settings
}

@Composable
private fun StepRowView(row: StepRow, trace: AgentTrace, actions: RunScreenActions) {
    var expanded by remember(row.id) { mutableStateOf(row.status == StepStatus.FAILED) }
    val title = when {
        row.category == "FILE" && row.subject != null -> stringResource(R.string.run_title_file, row.subject)
        row.category == "COMMAND" && row.subject != null -> stringResource(R.string.run_title_command, row.subject)
        row.category == "SEARCH" && row.subject != null -> stringResource(R.string.run_title_search, row.subject)
        else -> row.name.replace('_', ' ').replaceFirstChar(Char::titlecase)
    }
    val tint = if (row.status == StepStatus.FAILED) ErrColor else AiriTheme.onSurfaceVariant
    SectionCard(modifier = Modifier.padding(start = if (row.depth > 0) 20.dp else 0.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(categoryIcon(row.category), null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    title, color = AiriTheme.onBackground.copy(alpha = 0.92f), fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                row.durationMs?.let {
                    Text(
                        DateUtils.formatElapsedTime((it / 1000L).coerceAtLeast(1L)),
                        color = AiriTheme.outline, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                when (row.status) {
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = CosmicAccent)
                    StepStatus.DONE -> Icon(Icons.Outlined.CheckCircle, null, tint = OkColor, modifier = Modifier.size(16.dp))
                    StepStatus.FAILED -> Icon(Icons.Outlined.Error, null, tint = ErrColor, modifier = Modifier.size(16.dp))
                }
            }
            if (expanded) {
                row.inputPreview.forEach { (k, v) ->
                    Text("$k: $v", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                if (row.outputPreview.isNotBlank() && row.status != StepStatus.FAILED) {
                    Text(row.outputPreview, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
                if (row.status == StepStatus.FAILED) {
                    Text(
                        row.errorMessage ?: stringResource(R.string.run_error_default),
                        color = ErrColor, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis,
                    )
                    val action = suggestedActionFor(row)
                    if (action != SuggestedAction.NONE) {
                        val label = stringResource(
                            when (action) {
                                SuggestedAction.RETRY -> R.string.run_action_retry
                                SuggestedAction.SWITCH_MODEL -> R.string.run_action_switch_model
                                else -> R.string.run_action_grant
                            }
                        )
                        // Retrying one step is a scoped request to the agent, never a replay of the tool:
                        // stored arguments are truncated and redacted, so they cannot be re-executed.
                        val retryPrompt = stringResource(R.string.run_step_retry_prompt, stepRetryLabel(row))
                        OutlinedButton(
                            onClick = when (action) {
                                SuggestedAction.RETRY -> ({ actions.onFollowUp(retryPrompt) })
                                SuggestedAction.SWITCH_MODEL -> actions.onSwitchModel
                                else -> actions.onGrantPermission
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
                if (row.status == StepStatus.DONE && row.name == AgentFileWriter.TOOL_NAME) {
                    StepFileCard(trace, row)
                }
            }
        }
    }
}

@Composable
private fun StepFileCard(trace: AgentTrace, row: StepRow) {
    val context = LocalContext.current
    val filename = row.inputPreview.firstOrNull { it.first == "filename" }?.second ?: return
    val card = remember(filename) {
        AgentFileCards.fromExistingFile(filename, java.io.File(context.filesDir, AgentFileWriter.DIRECTORY_NAME))
    } ?: return
    AgentFileCardsRow(cards = listOf(card))
}

@Composable
private fun DecisionsGroup(decisions: List<AgentStep>) {
    var expanded by remember { mutableStateOf(false) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.run_decisions_title), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp),
                )
            }
            if (expanded) decisions.forEach { step ->
                Text(
                    step.outputSummary.ifBlank { step.name },
                    color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ResultSection(trace: AgentTrace, onOpenMessage: (String, Long) -> Unit) {
    val canOpen = !trace.inProgress && trace.sessionId.isNotBlank() && (trace.messageId ?: 0L) > 0L
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.run_result_title), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            if (trace.finalResult.isNotBlank()) {
                MarkdownText(rawText = trace.finalResult, baseFontSp = 13f, lineHeightSp = 19f)
            }
            Text(
                formatTraceDate(trace.timestamp, withSeconds = true),
                color = AiriTheme.outline, fontSize = 11.sp,
            )
            if (canOpen) {
                OutlinedButton(
                    onClick = { onOpenMessage(trace.sessionId, trace.messageId ?: 0L) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Forum, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.agent_logs_open_message), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ModelItemView(item: ModelRunItem) {
    when (item) {
        is ModelRunItem.Switch -> SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Replay, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.run_model_switch, item.from, item.to),
                        color = AiriTheme.onBackground.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
                item.reason?.let {
                    Text(
                        stringResource(R.string.run_model_switch_reason, it),
                        color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        is ModelRunItem.TurnDetail -> SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        item.turn?.let { stringResource(R.string.run_model_turn_title, it) }
                            ?: stringResource(R.string.run_model_title),
                        color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
                val target = listOfNotNull(item.model, item.provider).joinToString(" · ")
                if (target.isNotEmpty()) {
                    Text(
                        target,
                        color = AiriTheme.onBackground.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
                val finish = when (item.finish) {
                    TurnFinish.STOP -> stringResource(R.string.run_model_finish_stop)
                    TurnFinish.TOOL_CALLS -> stringResource(R.string.run_model_finish_tool_calls)
                    TurnFinish.LENGTH_LIMIT -> stringResource(R.string.run_model_finish_length)
                    TurnFinish.CONTENT_FILTER -> stringResource(R.string.run_model_finish_filtered)
                    TurnFinish.CANCELLED -> stringResource(R.string.run_model_finish_cancelled)
                    TurnFinish.ERROR -> stringResource(R.string.run_model_finish_error)
                    TurnFinish.CONNECTION_LOST -> stringResource(R.string.run_model_finish_connection)
                    TurnFinish.UNKNOWN -> null
                }
                val meta = listOfNotNull(
                    if (item.toolRequired) stringResource(R.string.run_model_tool_required) else null,
                    finish,
                    item.promptTokens?.let { stringResource(R.string.run_model_prompt_tokens, it) },
                    item.outputTokens?.let { stringResource(R.string.run_model_tokens, it) },
                    item.durationMs?.let { stringResource(R.string.run_model_duration, DateUtils.formatElapsedTime((it / 1000L).coerceAtLeast(0L))) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        is ModelRunItem.Turn -> SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.run_model_title), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    listOfNotNull(item.model, item.provider).joinToString(" · "),
                    color = AiriTheme.onBackground.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                val meta = listOfNotNull(
                    item.outputTokens?.let { stringResource(R.string.run_model_tokens, it) },
                    item.firstTokenMs?.let { stringResource(R.string.run_model_first_token, it.toInt()) },
                ).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
internal fun ApprovalCardView(model: ApprovalCardModel, onChoice: (ApprovalChoice) -> Unit) {
    // One tick per second while the card is on screen; the gate itself times out in the view model.
    val now by produceState(System.currentTimeMillis(), model.requestedAtMs) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val countdown = stringResource(R.string.run_approval_countdown, model.remainingSeconds(now))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(CosmicAccent.copy(alpha = 0.08f))
            .border(1.dp, CosmicAccent.copy(alpha = 0.55f), AIRIShapes.md)
            .padding(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PauseCircle, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.run_approval_title), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    countdown, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.semantics { contentDescription = countdown },
                )
            }
            Text(model.actionName, color = AiriTheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (model.description.isNotBlank()) {
                Text(model.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            if (model.destructive) {
                Text(stringResource(R.string.run_approval_irreversible), color = ErrColor, fontSize = 11.sp)
            }
            model.choices.forEach { choice ->
                val label = stringResource(
                    when (choice) {
                        ApprovalChoice.ALLOW_ONCE -> R.string.run_approval_allow_once
                        ApprovalChoice.ALLOW_THIS_RUN -> R.string.run_approval_allow_run
                        ApprovalChoice.DENY -> R.string.run_approval_deny
                    }
                )
                if (choice == ApprovalChoice.ALLOW_ONCE) {
                    Button(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) { Text(label, fontSize = 13.sp) }
                } else {
                    OutlinedButton(onClick = { onChoice(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, fontSize = 13.sp, color = if (choice == ApprovalChoice.DENY) ErrColor else Color.Unspecified)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FooterSection(trace: AgentTrace, footer: RunFooter, actions: RunScreenActions) {
    val context = LocalContext.current
    val copied = stringResource(R.string.agent_logs_copied)
    // submitFeedback persists the vote but does not re-emit the message, so the tap is mirrored here.
    var selected by remember(actions.feedback) { mutableStateOf(actions.feedback) }
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            footer.followUps.forEach { followUp ->
                val label = stringResource(
                    when (followUp) {
                        FollowUp.EXPLAIN_STEPS -> R.string.run_followup_explain
                        FollowUp.RETRY_FAILED -> R.string.run_followup_retry
                        FollowUp.VERIFY_AGAIN -> R.string.run_followup_verify
                    }
                )
                OutlinedButton(onClick = { actions.onFollowUp(label) }, modifier = Modifier.fillMaxWidth()) {
                    Text(label, fontSize = 13.sp)
                }
            }
            actions.onRate?.let { rate ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { selected = 1; rate(true) }) {
                        Icon(
                            Icons.Outlined.ThumbUp, contentDescription = stringResource(R.string.run_footer_like),
                            tint = if (selected == 1) CosmicAccent else AiriTheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { selected = -1; rate(false) }) {
                        Icon(
                            Icons.Outlined.ThumbDown, contentDescription = stringResource(R.string.run_footer_dislike),
                            tint = if (selected == -1) CosmicAccent else AiriTheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val onReason = actions.onReason
            if (onReason != null && ratingReasonsFor(selected).isNotEmpty()) {
                var chosen by remember(actions.reason) { mutableStateOf(actions.reason) }
                Text(stringResource(R.string.run_reason_title), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ratingReasonsFor(selected).forEach { reason ->
                        FilterChip(
                            selected = chosen == reason,
                            onClick = { chosen = reason; onReason(reason) },
                            label = {
                                Text(
                                    stringResource(
                                        when (reason) {
                                            RatingReason.INACCURATE -> R.string.run_reason_inaccurate
                                            RatingReason.INCOMPLETE -> R.string.run_reason_incomplete
                                            RatingReason.SLOW -> R.string.run_reason_slow
                                            RatingReason.OTHER -> R.string.run_reason_other
                                        }
                                    ),
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (footer.canRerun) {
                    Button(onClick = { actions.onRerun(trace) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Outlined.Replay, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.run_footer_rerun), fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("trace", trace.toClipboardText()))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.run_footer_copy), fontSize = 13.sp) }
            }
        }
    }
}
