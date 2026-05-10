package com.airi.assistant.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.ui.components.AiriScreenHeader
import com.airi.assistant.ui.components.NeuralDivider
import com.airi.assistant.ui.components.NeuralSearchBar
import com.airi.assistant.ui.components.NeuralSectionLabel
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onSessionSelected: () -> Unit
) {
    val sessions by viewModel.sessions.collectAsState()
    var pendingDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }
    var query by remember { mutableStateOf("") }

    val filtered = remember(sessions, query) {
        if (query.isBlank()) sessions
        else sessions.filter { it.firstMessage.contains(query, ignoreCase = true) }
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "المحادثات", onBack = onBack)
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NeuralSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "بحث في المحادثات...",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Forum, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                        Text(if (query.isBlank()) "لا توجد محادثات بعد" else "لا نتائج لـ \"$query\"", color = TextTertiary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item { NeuralSectionLabel("السجل") }
                    items(filtered, key = { it.id }) { session ->
                        HistorySessionCard(
                            session = session,
                            onClick = {
                                viewModel.loadSession(session.id)
                                onSessionSelected()
                            },
                            onLongClick = { pendingDelete = session }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Surface2,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("حذف المحادثة", fontWeight = FontWeight.Bold) },
            text = { Text("هل تريد حذف هذه المحادثة نهائياً؟") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDelete?.let { viewModel.deleteSession(it.id) }
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError)
                ) { Text("حذف", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("إلغاء", color = TextSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistorySessionCard(
    session: ChatSessionSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale("ar")) }
    val dateStr = remember(session.createdAt) { fmt.format(Date(session.createdAt)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryAccent.copy(0.12f))
                    .border(0.5.dp, PrimaryAccent.copy(0.25f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Chat, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(17.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.firstMessage.ifBlank { "محادثة جديدة" },
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(4.dp))
                Text(dateStr, color = TextTertiary, fontSize = 11.sp)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(17.dp))
        }
    }
}
