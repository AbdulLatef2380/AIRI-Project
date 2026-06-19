package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val memoryMessages by viewModel.memoryEntries.collectAsState()
    val memoryCount    by viewModel.memoryCount.collectAsState()
    var showConfirm    by remember { mutableStateOf(false) }
    val snackbarHost   = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.loadMemoryEntries() }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.memory_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                        Text("$memoryCount stored interactions", fontSize = 11.sp, color = CosmicAccent.copy(alpha = 0.75f))
                    }
                },
                actions = {
                    IconButton(onClick = { showConfirm = true }) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear_memory), tint = Color(0xFFFF6B6B))
                    }
                }
            )
        }
    ) { padding ->
        if (memoryMessages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Psychology, null, tint = CosmicAccent.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.memory_no_memory), color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.memory_no_memory_desc), color = AiriTheme.outline, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Summary card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(CosmicAccent.copy(alpha = 0.12f), Color.Transparent)))
                            .border(1.dp, CosmicAccent.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Column {
                            Text(stringResource(R.string.memory_episodic_section), fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 13.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "AIRI uses a sliding window of recent interactions as context for each new message. The full history is stored in the local database.",
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                items(memoryMessages, key = { it.id }) { msg ->
                    MemoryEntryCard(msg)
                }
            }
        }
    }

    // Confirm clear dialog
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor   = Color(0xFF12162E),
            titleContentColor = Color.White,
            textContentColor  = Color.White.copy(alpha = 0.7f),
            shape            = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.clear_all_memory), fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.memory_clear_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        viewModel.clearMemory()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFCC3333))
                ) { Text(stringResource(R.string.memory_clear_button)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant) }
            }
        )
    }
}

@Composable
private fun MemoryEntryCard(msg: ChatMessage) {
    val isUser    = msg.role == "user"
    val timestamp = remember(msg.timestamp) {
        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    else        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                )
                .background(
                    if (isUser) CosmicAccent.copy(alpha = 0.12f)
                    else        Color.White.copy(alpha = 0.05f)
                )
                .border(
                    1.dp,
                    if (isUser) CosmicAccent.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                    if (isUser) RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp)
                    else        RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isUser) "You" else "AIRI",
                        fontSize = 10.sp,
                        color = if (isUser) CosmicAccent.copy(alpha = 0.8f) else Color.White.copy(alpha = 0.4f),
                        fontWeight = FontWeight.Bold
                    )
                    Text(timestamp, fontSize = 10.sp, color = AiriTheme.outline)
                }
                Spacer(Modifier.height(4.dp))
                Text(msg.content, color = AiriTheme.onBackground.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 19.sp)
            }
        }
    }
}
