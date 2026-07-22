package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.R
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxLogEntry
import com.airi.assistant.agent.sandbox.SandboxSession
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

private val SandboxTermBg      = Color(0xFF090C18)
private val SandboxLogBg       = Color(0xFF0C0F1C)
private val SandboxBorder      = Color(0xFF1E2338)
private val SandboxSuccessText = Color(0xFF4CAF50)
private val SandboxErrorText   = Color(0xFFFF6B6B)
private val SandboxWarnText    = Color(0xFFFFD54F)
private val SandboxInfoText    = Color(0xFFCDD5E0).copy(alpha = 0.75f)
private val SandboxTimeColor   = Color(0xFF546E7A)
private val SandboxTagColor    = Color(0xFF7C6FF0)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SandboxWorkspaceScreen(onBack: () -> Unit) {
    val sandboxManager  = ServiceLocator.sandboxManager
    val activeSessions  by sandboxManager.activeSessions.collectAsStateWithLifecycle()
    var selectedSession by remember { mutableStateOf<SandboxSession?>(null) }
    var commandInput    by remember { mutableStateOf("") }
    var isExecuting     by remember { mutableStateOf(false) }
    val scope           = rememberCoroutineScope()
    val logState        = rememberLazyListState()
    val snackbar        = remember { SnackbarHostState() }

    val session = selectedSession ?: activeSessions.firstOrNull()

    // Poll logs every 250 ms so the list stays live without Flow wiring in SandboxSession
    var logs by remember { mutableStateOf(session?.execLog ?: emptyList<SandboxLogEntry>()) }
    LaunchedEffect(session?.sessionId) {
        while (true) {
            logs = session?.execLog ?: emptyList()
            kotlinx.coroutines.delay(250)
        }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scope.launch { logState.animateScrollToItem(logs.size - 1) }
        }
    }

    Scaffold(
        containerColor = SandboxTermBg,
        snackbarHost   = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = Color(0xFF0F1220), shadowElevation = 0.dp) {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F1220)),
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Outlined.ArrowBack, null, tint = Color(0xFFCDD5E0).copy(0.7f))
                            }
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Outlined.Terminal, null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                                Text(
                                    stringResource(R.string.sandbox_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFFCDD5E0)
                                )
                                if (activeSessions.isNotEmpty()) {
                                    Surface(shape = AIRIShapes.xs.copy(topStart = 4.dp), color = CosmicAccent.copy(0.18f)) {
                                        Text(
                                            "${activeSessions.size}",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = CosmicAccent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            // Restart session
                            if (session != null) {
                                IconButton(onClick = {
                                    scope.launch {
                                        sandboxManager.closeSession(session.sessionId)
                                        sandboxManager.createSession(session.label)
                                        selectedSession = null
                                    }
                                }) {
                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.sandbox_restart_cd), tint = SandboxInfoText, modifier = Modifier.size(20.dp))
                                }
                            }
                            // New session
                            IconButton(onClick = { scope.launch { sandboxManager.createSession("Session ${activeSessions.size + 1}") } }) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.sandbox_new_session_cd), tint = CosmicAccent, modifier = Modifier.size(20.dp))
                            }
                        }
                    )

                    // Session selector tab bar
                    if (activeSessions.isNotEmpty()) {
                        SessionTabBar(
                            sessions = activeSessions,
                            selected = session,
                            onSelect = { selectedSession = it },
                            onClose  = { sandboxManager.closeSession(it.sessionId); if (selectedSession?.sessionId == it.sessionId) selectedSession = null }
                        )
                    }

                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(SandboxBorder))
                }
            }
        }
    ) { padding ->
        if (activeSessions.isEmpty()) {
            SandboxEmptyState(onCreate = { scope.launch { sandboxManager.createSession("Workspace 1") } })
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).background(SandboxTermBg)) {
                // Session info strip
                session?.let { sess ->
                    SandboxInfoStrip(session = sess, isExecuting = isExecuting)
                }

                // Execution log
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 0.dp)
                ) {
                    if (logs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Outlined.Terminal, null, tint = SandboxTimeColor, modifier = Modifier.size(28.dp))
                                Text(stringResource(R.string.sandbox_no_executions), fontSize = 12.sp, color = SandboxTimeColor, fontFamily = FontFamily.Monospace)
                            }
                        }
                    } else {
                        LazyColumn(
                            state = logState,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            items(logs, key = { "${it.timestampMs}_${it.message.take(12)}" }) { entry ->
                                SandboxLogRow(entry = entry)
                            }
                            if (isExecuting) {
                                item(key = "running") {
                                    Row(
                                        modifier = Modifier.padding(top = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = SandboxSuccessText,
                                            modifier = Modifier.size(9.dp),
                                            strokeWidth = 1.5.dp
                                        )
                                        Text(
                                            "Executing…",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SandboxSuccessText.copy(0.65f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Command input
                Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF0F1220))) {
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(SandboxBorder))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "$",
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isExecuting) SandboxWarnText else SandboxTagColor
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = commandInput,
                            onValueChange = { commandInput = it },
                            enabled = !isExecuting,
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = Color(0xFF4FC3F7),
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                            decorationBox = { inner ->
                                if (commandInput.isEmpty()) {
                                    Text(stringResource(R.string.sandbox_command_placeholder), fontSize = 13.sp, color = SandboxTimeColor, fontFamily = FontFamily.Monospace)
                                }
                                inner()
                            }
                        )
                        // Cancel / Run button
                        if (isExecuting) {
                            Surface(
                                onClick = { /* cancel */ },
                                shape = AIRIShapes.xs,
                                color = SandboxErrorText.copy(0.15f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = SandboxErrorText,
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp
                                    )
                                    Text(stringResource(R.string.sandbox_cancel_execution), fontSize = 11.sp, color = SandboxErrorText)
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(AIRIShapes.xs)
                                    .background(
                                        if (commandInput.isNotBlank()) CosmicAccent.copy(0.22f) else SandboxBorder
                                    )
                                    .clickable(enabled = commandInput.isNotBlank()) {
                                        val cmd = commandInput.trim()
                                        val target = session ?: return@clickable
                                        scope.launch {
                                            isExecuting = true
                                            SandboxExecutor(target).execute(
                                                SandboxExecutor.SandboxTask(
                                                    type    = SandboxExecutor.TaskType.SHELL_COMMAND,
                                                    command = cmd
                                                )
                                            )
                                            commandInput = ""
                                            isExecuting  = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.PlayArrow,
                                    stringResource(R.string.sandbox_run_cd),
                                    tint = if (commandInput.isNotBlank()) CosmicAccent else SandboxTimeColor,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<SandboxSession>,
    selected: SandboxSession?,
    onSelect: (SandboxSession) -> Unit,
    onClose:  (SandboxSession) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        sessions.forEach { sess ->
            val isSelected = sess.sessionId == selected?.sessionId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(AIRIShapes.xs)
                    .background(if (isSelected) CosmicAccent.copy(0.18f) else Color(0xFF111525))
                    .border(0.5.dp, if (isSelected) CosmicAccent.copy(0.4f) else SandboxBorder, AIRIShapes.xs)
                    .clickable { onSelect(sess) }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Box(
                    modifier = Modifier.size(6.dp).clip(CircleShape)
                        .background(if (isSelected) SandboxSuccessText else SandboxTimeColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    sess.label.take(20),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (isSelected) CosmicAccent else SandboxInfoText
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Close, null,
                    tint = SandboxTimeColor,
                    modifier = Modifier.size(11.dp).clickable { onClose(sess) }
                )
            }
        }
    }
}

@Composable
private fun SandboxInfoStrip(session: SandboxSession, isExecuting: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0C0F1C)).padding(horizontal = 12.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.FolderOpen, null, tint = SandboxTimeColor, modifier = Modifier.size(12.dp))
        Text(
            session.workspaceDir.absolutePath,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = SandboxTimeColor,
            modifier = Modifier.weight(1f)
        )
        val age = (System.currentTimeMillis() - session.createdAtMs) / 1000
        Text(
            "${age}s",
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            color = SandboxTimeColor
        )
    }
}

@Composable
private fun SandboxLogRow(entry: SandboxLogEntry) {
    val (levelColor, prefix) = when (entry.level) {
        "ERROR" -> SandboxErrorText   to "✕"
        "WARN"  -> SandboxWarnText    to "⚠"
        "OK",
        "SUCCESS" -> SandboxSuccessText to "✓"
        else    -> SandboxInfoText    to "›"
    }
    val time = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestampMs))
    }
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(vertical = 1.dp)
    ) {
        Text(time,   fontSize = 9.sp,  color = SandboxTimeColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(60.dp))
        Text(prefix, fontSize = 10.sp, color = levelColor, fontFamily = FontFamily.Monospace, modifier = Modifier.width(12.dp))
        Text(entry.message, fontSize = 11.sp, color = SandboxInfoText, fontFamily = FontFamily.Monospace, lineHeight = 15.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SandboxEmptyState(onCreate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier.size(64.dp).clip(AIRIShapes.md).background(CosmicAccent.copy(0.10f))
                    .border(0.5.dp, CosmicAccent.copy(0.25f), AIRIShapes.md),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = CosmicAccent.copy(0.6f), modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.sandbox_no_sessions), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFCDD5E0).copy(0.8f))
            Text(stringResource(R.string.sandbox_no_sessions_desc), fontSize = 12.sp, color = SandboxTimeColor, fontFamily = FontFamily.Monospace)
            Button(
                onClick = onCreate,
                shape   = AIRIShapes.sm,
                colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent.copy(0.85f)),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.sandbox_new_session_button), fontFamily = FontFamily.Monospace)
            }
        }
    }
}
