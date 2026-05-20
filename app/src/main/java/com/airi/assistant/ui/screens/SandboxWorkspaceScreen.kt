package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxLogEntry
import com.airi.assistant.agent.sandbox.SandboxSession
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * SandboxWorkspaceScreen — live sandbox session viewer and execution console.
 *
 * Shows:
 *  - Active sandbox sessions with workspace path
 *  - Per-session execution log (monospace terminal style)
 *  - Quick file-write task launcher
 *  - Quick shell command runner
 *  - Session close controls
 *
 * Wired to [SandboxManager] via [ServiceLocator].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxWorkspaceScreen(onBack: () -> Unit) {
    val sandboxManager = ServiceLocator.sandboxManager
    val activeSessions by sandboxManager.activeSessions.collectAsStateWithLifecycle()
    var selectedSession by remember { mutableStateOf<SandboxSession?>(null) }
    var commandInput by remember { mutableStateOf("") }
    var isExecuting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val logState = rememberLazyListState()

    // Auto-scroll to latest log entry
    val session = selectedSession ?: activeSessions.firstOrNull()
    val logs = session?.execLog ?: emptyList()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logState.animateScrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sandbox", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                        if (activeSessions.isNotEmpty()) {
                            Box(modifier = Modifier.clip(CircleShape).background(CosmicAccent.copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text("${activeSessions.size} active", fontSize = 11.sp, color = CosmicAccent)
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch { sandboxManager.createSession("New Workspace") }
                    }) {
                        Icon(Icons.Outlined.Add, contentDescription = "New session", tint = CosmicAccent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = CosmicBlack
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ── Session tabs ──────────────────────────────────────────────
            if (activeSessions.isEmpty()) {
                SandboxEmptyState(onCreate = {
                    scope.launch { sandboxManager.createSession("New Workspace") }
                })
            } else {
                // Session selector chips
                SessionSelectorRow(
                    sessions        = activeSessions,
                    selectedSession = session,
                    onSelect        = { selectedSession = it },
                    onClose         = { sandboxManager.closeSession(it.sessionId) }
                )

                HorizontalDivider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))

                // Session info header
                session?.let { sess ->
                    SandboxSessionHeader(session = sess)
                }

                // ── Execution log terminal ────────────────────────────────
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF060910))
                        .border(0.5.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                ) {
                    if (logs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No executions yet — run a command below", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f))
                        }
                    } else {
                        LazyColumn(
                            state           = logState,
                            modifier        = Modifier.fillMaxSize().padding(10.dp),
                            contentPadding  = PaddingValues(vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(items = logs, key = { it.timestampMs.toString() + it.message.take(16) }) { entry ->
                                LogEntryRow(entry = entry)
                            }
                        }
                    }
                }

                // ── Command input ─────────────────────────────────────────
                Spacer(Modifier.height(8.dp))
                SandboxCommandBar(
                    value       = commandInput,
                    onValue     = { commandInput = it },
                    isExecuting = isExecuting,
                    onRun       = {
                        val cmd = commandInput.trim()
                        if (cmd.isBlank()) return@SandboxCommandBar
                        val targetSession = session ?: return@SandboxCommandBar
                        scope.launch {
                            isExecuting = true
                            SandboxExecutor(targetSession).execute(
                                SandboxExecutor.SandboxTask(
                                    type    = SandboxExecutor.TaskType.SHELL_COMMAND,
                                    command = cmd
                                )
                            )
                            commandInput = ""
                            isExecuting = false
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SandboxEmptyState(onCreate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Terminal, contentDescription = null, tint = CosmicAccent.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
            Text("No active sandbox sessions", fontSize = 15.sp, color = Color.White.copy(alpha = 0.6f))
            Text("Create a session to start running isolated tasks", fontSize = 13.sp, color = Color.White.copy(alpha = 0.35f))
            Button(
                onClick = onCreate,
                colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent.copy(alpha = 0.85f)),
                shape   = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("New Session")
            }
        }
    }
}

@Composable
private fun SessionSelectorRow(
    sessions: List<SandboxSession>,
    selectedSession: SandboxSession?,
    onSelect: (SandboxSession) -> Unit,
    onClose: (SandboxSession) -> Unit
) {
    Row(
        modifier             = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        sessions.forEach { sess ->
            val isSelected = sess.sessionId == selectedSession?.sessionId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) CosmicAccent.copy(alpha = 0.18f) else SurfaceRaised)
                    .border(0.5.dp, if (isSelected) CosmicAccent.copy(alpha = 0.4f) else DividerColor, RoundedCornerShape(8.dp))
                    .clickable { onSelect(sess) }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(sess.label.take(18), fontSize = 12.sp, color = if (isSelected) CosmicAccent else Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Close, contentDescription = "Close session",
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.size(12.dp).clickable { onClose(sess) }
                )
            }
        }
    }
}

@Composable
private fun SandboxSessionHeader(session: SandboxSession) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = CosmicAccent.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text     = session.workspaceDir.absolutePath,
            fontSize = 10.sp,
            color    = Color.White.copy(alpha = 0.35f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        val age = (System.currentTimeMillis() - session.createdAtMs) / 1000
        Text("${age}s", fontSize = 10.sp, color = Color.White.copy(alpha = 0.25f), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LogEntryRow(entry: SandboxLogEntry) {
    val (levelColor, prefix) = when (entry.level) {
        "ERROR" -> SemanticError  to "✕"
        "WARN"  -> SemanticWarn   to "⚠"
        else    -> Color(0xFF4FC3F7) to "›"
    }
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestampMs))
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(time,   fontSize = 9.sp,  color = Color.White.copy(alpha = 0.25f), fontFamily = FontFamily.Monospace, modifier = Modifier.width(72.dp))
        Text(prefix, fontSize = 10.sp, color = levelColor,                      fontFamily = FontFamily.Monospace, modifier = Modifier.width(12.dp))
        Text(entry.message, fontSize = 10.sp, color = Color.White.copy(alpha = 0.75f), fontFamily = FontFamily.Monospace, lineHeight = 14.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SandboxCommandBar(
    value: String, onValue: (String) -> Unit,
    isExecuting: Boolean, onRun: () -> Unit, modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text("$", fontSize = 13.sp, color = SemanticSuccess, fontFamily = FontFamily.Monospace)
        OutlinedTextField(
            value          = value,
            onValueChange  = onValue,
            placeholder    = { Text("shell command…", fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f), fontFamily = FontFamily.Monospace) },
            singleLine     = true,
            modifier       = Modifier.weight(1f).height(46.dp),
            textStyle      = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White),
            colors         = OutlinedTextFieldDefaults.colors(
                focusedBorderColor   = CosmicAccent.copy(alpha = 0.5f),
                unfocusedBorderColor = DividerColor,
                focusedContainerColor   = Color(0xFF0C0F1A),
                unfocusedContainerColor = Color(0xFF0C0F1A)
            ),
            shape = RoundedCornerShape(10.dp)
        )
        IconButton(
            onClick  = onRun,
            enabled  = value.isNotBlank() && !isExecuting,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(
                if (value.isNotBlank() && !isExecuting) CosmicAccent.copy(alpha = 0.85f) else SurfaceRaised
            )
        ) {
            if (isExecuting) {
                CircularProgressIndicator(color = CosmicAccent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.PlayArrow, contentDescription = "Run", tint = Color.White)
            }
        }
    }
}
