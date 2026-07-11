package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.workspace.ArtifactManager
import com.airi.assistant.workspace.WorkspaceRuntime
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.theme.AiriTheme
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * WorkspaceScreen — Claude Artifacts / Replit-style workspace UI.
 *
 * Shows:
 *  - Active workspace sessions (tabs)
 *  - Generated artifacts per session (cards with preview)
 *  - Quick artifact creation
 *  - Workspace management (create / close)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    onBack: () -> Unit,
    onOpenChat: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    /** Task 5.2/5.3: "prototype", "wireframe", or null for general workspace. */
    sessionType: String? = null
) {
    val workspaceRuntime = ServiceLocator.workspaceRuntime
    val artifactManager  = ServiceLocator.artifactManager
    val scope            = rememberCoroutineScope()

    val sessions       by workspaceRuntime.allSessions.collectAsStateWithLifecycle()
    val activeSession  by workspaceRuntime.activeSession.collectAsStateWithLifecycle()
    val allArtifacts   by artifactManager.allArtifacts.collectAsStateWithLifecycle()
    val artifacts      = remember(allArtifacts, activeSession) {
        activeSession?.let { artifactManager.forSession(it.sessionId) } ?: emptyList()
    }

    var showNewWorkspace   by remember { mutableStateOf(false) }
    var newWorkspaceName   by remember { mutableStateOf("") }
    var selectedArtifactId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.workspace_title), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                        if (sessions.isNotEmpty()) {
                            Box(modifier = Modifier.clip(CircleShape).background(CosmicAccent.copy(0.18f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                                Text("${sessions.size}", fontSize = 11.sp, color = CosmicAccent)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null, tint = AiriTheme.onBackground) } },
                actions = {
                    IconButton(onClick = onOpenChat) { Icon(Icons.Outlined.Chat, null, tint = AiriTheme.onBackground.copy(0.7f)) }
                    IconButton(onClick = { showNewWorkspace = true }) { Icon(Icons.Outlined.Add, null, tint = CosmicAccent) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (sessions.isEmpty()) {
                WorkspaceEmptyState { showNewWorkspace = true }
            } else {
                // Horizontal session tabs
                LazyRow(
                    modifier              = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.sessionId }) { session ->
                        val isActive = session.sessionId == activeSession?.sessionId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isActive) CosmicAccent.copy(0.18f) else SurfaceRaised)
                                .border(0.5.dp, if (isActive) CosmicAccent.copy(0.4f) else DividerColor, RoundedCornerShape(10.dp))
                                .clickable { workspaceRuntime.setActive(session.sessionId) }
                                .padding(horizontal = 12.dp, vertical = 7.dp)
                        ) {
                            Text(session.name.take(20), fontSize = 13.sp, color = if (isActive) CosmicAccent else Color.White.copy(0.7f))
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.Close, null, tint = AiriTheme.onBackground.copy(0.3f),
                                modifier = Modifier.size(12.dp).clickable { workspaceRuntime.closeSession(session.sessionId) })
                        }
                    }
                }

                Divider(color = AiriTheme.outline, modifier = Modifier.padding(horizontal = 12.dp))
                if (artifacts.isEmpty()) {
                    ArtifactEmptyState(onCreateFromChat = onOpenChat)
                } else {
                    LazyColumn(
                        modifier              = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                        verticalArrangement   = Arrangement.spacedBy(10.dp),
                        contentPadding        = PaddingValues(vertical = 12.dp)
                    ) {
                        items(artifacts, key = { it.id }) { artifact ->
                            ArtifactCard(
                                artifact   = artifact,
                                isSelected = selectedArtifactId == artifact.id,
                                onClick    = { selectedArtifactId = if (selectedArtifactId == artifact.id) null else artifact.id },
                                onDelete   = { artifactManager.deleteArtifact(artifact.id) },
                                // : Navigate to ArtifactPreviewScreen on long-press preview tap.
                                // Full artifact content is read from filePath on IO dispatcher to
                                // avoid blocking the main thread; falls back to previewSnippet if the
                                // file is absent or unreadable (e.g. artifact deleted externally).
                                onPreview  = { selected ->
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        val content = try {
                                            val file = java.io.File(selected.filePath)
                                            if (file.exists() && file.canRead()) file.readText() else null
                                        } catch (_: Exception) { null }
                                            ?: selected.previewSnippet.orEmpty()
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            onNavigate(AiriRoute.artifactPreview(
                                                selected.type.name.lowercase(), content))
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        if (showNewWorkspace) {
            AlertDialog(
                onDismissRequest = { showNewWorkspace = false },
                title = { Text(stringResource(R.string.workspace_new_dialog_title), color = AiriTheme.onBackground) },
                text = {
                    OutlinedTextField(
                        value         = newWorkspaceName,
                        onValueChange = { newWorkspaceName = it },
                        label         = { Text(stringResource(R.string.workspace_name_label), fontSize = 13.sp) },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = CosmicAccent.copy(0.6f),
                            unfocusedBorderColor = DividerColor,
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White
                        )
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newWorkspaceName.isNotBlank()) {
                            workspaceRuntime.createSession(newWorkspaceName)
                            newWorkspaceName = ""
                            showNewWorkspace = false
                        }
                    }) { Text(stringResource(R.string.create), color = CosmicAccent) }
                },
                dismissButton = {
                    TextButton(onClick = { showNewWorkspace = false }) { Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.5f)) }
                },
                containerColor = MaterialTheme.colorScheme.surface
            )
        }
    }
}

@Composable
private fun ArtifactCard(
    artifact:   ArtifactManager.Artifact,
    isSelected: Boolean,
    onClick:    () -> Unit,
    onDelete:   () -> Unit,
    // : navigate to ArtifactPreviewScreen when the user taps "Preview"
    onPreview:  (ArtifactManager.Artifact) -> Unit = {}
) {
    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = if (isSelected) CosmicAccent.copy(0.08f) else SurfaceRaised,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.clickable(onClick = onClick).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(artifact.type.emoji, fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(artifact.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    Text("${artifact.type.name} · v${artifact.version} · ${artifact.sizeBytes / 1024}KB",
                        fontSize = 11.sp, color = AiriTheme.onBackground.copy(0.4f))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = SemanticError.copy(0.6f), modifier = Modifier.size(14.dp))
                }
            }
            if (!artifact.description.isBlank()) {
                Text(artifact.description, fontSize = 12.sp, color = AiriTheme.onBackground.copy(0.55f))
            }
            AnimatedVisibility(visible = isSelected && !artifact.previewSnippet.isNullOrBlank()) {
                Text(
                    artifact.previewSnippet ?: "",
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color      = AiriTheme.onBackground.copy(0.6f),
                    lineHeight = 16.sp,
                    maxLines   = 12,
                    modifier   = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface).padding(10.dp)
                )
                // : "Preview" button → ArtifactPreviewScreen (sandboxed WebView/Markdown/Code)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = { onPreview(artifact) },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Outlined.OpenInNew, null,
                            modifier = Modifier.size(13.dp), tint = CosmicAccent)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.workspace_preview), fontSize = 11.sp, color = CosmicAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceEmptyState(onCreate: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Outlined.WorkOutline, null, tint = CosmicAccent.copy(0.5f), modifier = Modifier.size(48.dp))
            Text(stringResource(R.string.workspace_no_workspaces), fontSize = 16.sp, color = AiriTheme.onBackground.copy(0.6f))
            Text(stringResource(R.string.workspace_no_workspaces_desc), fontSize = 13.sp, color = AiriTheme.onBackground.copy(0.35f))
            Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent.copy(0.85f)), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.workspace_new_button))
            }
        }
    }
}

@Composable
private fun ArtifactEmptyState(onCreateFromChat: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, tint = CosmicAccent.copy(0.4f), modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.workspace_no_artifacts), fontSize = 15.sp, color = AiriTheme.onBackground.copy(0.5f))
            Text(stringResource(R.string.workspace_no_artifacts_desc), fontSize = 13.sp, color = AiriTheme.onBackground.copy(0.3f))
            OutlinedButton(onClick = onCreateFromChat, shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CosmicAccent.copy(0.4f))) {
                Text(stringResource(R.string.workspace_open_chat), color = CosmicAccent, fontSize = 13.sp)
            }
        }
    }
}
