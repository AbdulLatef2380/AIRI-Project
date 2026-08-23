package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import androidx.compose.material3.MaterialTheme
import com.airi.assistant.ui.theme.SurfaceCard
import com.airi.assistant.ui.theme.SurfaceRaised
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSessionSelected: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    var sessionToDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var sessionToRename by remember { mutableStateOf<ChatSessionSummary?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.92f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.history_title),
                        color = AiriTheme.onBackground,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    // Spacer to balance the close icon
                    Spacer(Modifier.size(48.dp))
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(AIRIShapes.md)
                    .background(CosmicAccent.copy(alpha = 0.10f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.28f), AIRIShapes.md)
                    .clickable { viewModel.clearMessages(); onSessionSelected() }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(CosmicAccent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = AiriTheme.onBackground, modifier = Modifier.size(18.dp))
                }
                Text(
                    text = stringResource(R.string.new_conversation),
                    color = CosmicAccent,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(Modifier.height(16.dp))

            if (sessions.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(AiriTheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Forum,
                                contentDescription = null,
                                tint = AiriTheme.onSurfaceVariant.copy(alpha = 0.30f),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.history_no_sessions),
                            color = AiriTheme.onSurfaceVariant.copy(alpha = 0.38f),
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        HistorySessionItem(
                            session = session,
                            onSelect = {
                                viewModel.loadSession(session.id)
                                onSessionSelected()
                            },
                            onDelete = { sessionToDelete = session },
                            onRename = { sessionToRename = session },
                            onTogglePin = { viewModel.setSessionPinned(session.id, !session.isPinned) }
                        )
                    }
                }
            }
        }
    }

    sessionToRename?.let { session ->
        var renameDraft by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { sessionToRename = null },
            containerColor = AiriTheme.surface,
            title = { Text(stringResource(R.string.rename_chat_title), color = AiriTheme.onBackground) },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text(stringResource(R.string.rename_chat_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameSession(session.id, renameDraft)
                        sessionToRename = null
                    },
                    enabled = renameDraft.trim().isNotEmpty()
                ) { Text(stringResource(R.string.save), color = CosmicAccent) }
            },
            dismissButton = {
                TextButton(onClick = { sessionToRename = null }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    // Delete confirmation
    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null },
            containerColor = AiriTheme.surface,
            titleContentColor = AiriTheme.onSurface,
            textContentColor = AiriTheme.onSurface.copy(alpha = 0.75f),
            shape = AIRIShapes.xl,
            title = {
                Text(
                    stringResource(R.string.confirm_delete_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End
                )
            },
            text = {
                Text(
                    stringResource(R.string.confirm_delete_body),
                    textAlign = TextAlign.End,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(session.id)
                        sessionToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SemanticError,
                        contentColor = AiriTheme.onSurface
                    ),
                    shape = AIRIShapes.md,
                    modifier = Modifier.fillMaxWidth(0.5f)
                ) {
                    Text(stringResource(R.string.delete), fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { sessionToDelete = null },
                    shape = AIRIShapes.md,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.onSurface.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth(0.45f)
                ) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(alpha = 0.75f))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistorySessionItem(
    session: ChatSessionSummary,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onTogglePin: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var showActions by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.md)
            .background(if (showActions) CosmicAccent.copy(alpha = 0.06f) else Color.Transparent)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = { showActions = true }
            )
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (session.isPinned) {
                Icon(
                    Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CosmicAccent)
                )
            }
            Box {
                IconButton(
                    onClick = { showActions = true },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.cd_options),
                        tint = CosmicAccent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showActions,
                    onDismissRequest = { showActions = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(if (session.isPinned) R.string.unpin_chat else R.string.pin_chat)) },
                        onClick = { showActions = false; onTogglePin() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_chat)) },
                        onClick = { showActions = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete), color = SemanticError) },
                        onClick = { showActions = false; onDelete() }
                    )
                }
            }
        }

        // Right side: title + preview + time (RTL)
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.weight(1f).padding(start = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = dateFormat.format(Date(session.updatedAt)),
                    color = AiriTheme.outline,
                    fontSize = 11.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = session.title.ifBlank { stringResource(R.string.history_session_default) },
                    color = AiriTheme.onBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = session.lastMessage.orEmpty().ifBlank { "..." },
                color = AiriTheme.onSurfaceVariant.copy(alpha = 0.42f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    Divider(
        color = AiriTheme.outline,
        modifier = Modifier.padding(start = 14.dp)
    )
}
