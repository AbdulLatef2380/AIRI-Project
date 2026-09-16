package com.airi.assistant.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(viewModel: ChatViewModel, onBack: () -> Unit, onSessionSelected: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessions by viewModel.sessions.collectAsState()
    val favoriteSessionIds by viewModel.favoriteSessionIds.collectAsState()
    var sessionToDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var renameDraft by remember { mutableStateOf("") }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                title = { Text("المحادثات", color = AiriTheme.onBackground, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "إغلاق", tint = AiriTheme.onBackground) } },
                actions = { Spacer(Modifier.size(48.dp)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(AIRIShapes.md).background(CosmicAccent.copy(.12f)).clickable { viewModel.clearMessages(); onSessionSelected() }.padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Add, null, tint = CosmicAccent, modifier = Modifier.size(22.dp))
                Text("محادثة جديدة", color = CosmicAccent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(18.dp))
            if (sessions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("لا توجد محادثات محفوظة", color = AiriTheme.onSurfaceVariant.copy(.55f), textAlign = TextAlign.Center) }
            } else {
                Text("المحادثات الأخيرة", color = AiriTheme.onSurfaceVariant.copy(.6f), fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), textAlign = TextAlign.End)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(sessions, key = { it.id }) { session ->
                        HistorySessionItem(
                            session = session,
                            onSelect = { viewModel.loadSession(session.id); onSessionSelected() },
                            onDelete = { sessionToDelete = session },
                            onRename = { renameDraft = session.title; renameTarget = session },
                            onPin = { viewModel.setSessionPinned(session.id, !session.isPinned) },
                            isFavorite = session.id in favoriteSessionIds,
                            onFavorite = { viewModel.setSessionFavorite(session.id, session.id !in favoriteSessionIds) },
                            onArchive = { viewModel.archiveSession(session.id) },
                            onShare = { shareSession(context, session) }
                        )
                    }
                }
            }
        }
    }

    sessionToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { sessionToDelete = null }, containerColor = AiriTheme.surface,
            title = { Text("حذف المحادثة؟", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            text = { Text("سيتم حذف المحادثة ورسائلها نهائياً.", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            confirmButton = { TextButton(onClick = { viewModel.deleteSession(session.id); sessionToDelete = null }) { Text("حذف", color = SemanticError) } },
            dismissButton = { TextButton(onClick = { sessionToDelete = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    renameTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { renameTarget = null }, containerColor = AiriTheme.surface,
            title = { Text("تغيير اسم المحادثة", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End) },
            text = { OutlinedTextField(value = renameDraft, onValueChange = { renameDraft = it.take(80) }, singleLine = true, label = { Text(stringResource(R.string.rename_chat_hint)) }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { viewModel.renameSession(session.id, renameDraft); renameTarget = null }) { Text(stringResource(R.string.rename_chat)) } },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HistorySessionItem(session: ChatSessionSummary, onSelect: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit, onPin: () -> Unit, isFavorite: Boolean, onFavorite: () -> Unit, onArchive: () -> Unit, onShare: () -> Unit) {
    val time = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    var actions by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(AIRIShapes.md).background(if (actions) AiriTheme.surfaceVariant else AiriTheme.surface).combinedClickable(onClick = onSelect, onLongClick = { actions = !actions }).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (session.isPinned) Icon(Icons.Filled.PushPin, "مثبتة", tint = CosmicAccent, modifier = Modifier.size(15.dp))
            Text(time.format(Date(session.updatedAt)), color = AiriTheme.onSurfaceVariant.copy(.55f), fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(session.title.ifBlank { "محادثة جديدة" }, color = AiriTheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Text(session.lastMessage.orEmpty().ifBlank { "لا توجد رسائل بعد" }, color = AiriTheme.onSurfaceVariant.copy(.55f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        if (actions) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                ActionIcon(Icons.Outlined.DeleteOutline, "حذف", SemanticError, onDelete)
                ActionIcon(Icons.Outlined.Archive, "أرشفة", AiriTheme.onSurfaceVariant, onArchive)
                ActionIcon(Icons.Outlined.FavoriteBorder, if (isFavorite) "مفضلة" else "إضافة إلى المفضلة", CosmicAccent, onFavorite)
                ActionIcon(Icons.Outlined.Share, "مشاركة", AiriTheme.onSurfaceVariant, onShare)
                ActionIcon(Icons.Outlined.Edit, "تسمية", AiriTheme.onSurfaceVariant, onRename)
                ActionIcon(if (session.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, stringResource(R.string.pin_chat), CosmicAccent, onPin)
                Text("إجراءات المحادثة", color = AiriTheme.onSurfaceVariant.copy(.55f), fontSize = 11.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { Icon(icon, label, tint = tint, modifier = Modifier.size(18.dp)) }
}

private fun shareSession(context: Context, session: ChatSessionSummary) {
    val intent = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${session.title}\n\n${session.lastMessage.orEmpty()}") }
    context.startActivity(Intent.createChooser(intent, "مشاركة المحادثة"))
}
