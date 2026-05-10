package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun MemoryScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val memories by viewModel.memories.collectAsState()
    var query    by remember { mutableStateOf("") }

    val filtered = remember(memories, query) {
        if (query.isBlank()) memories
        else memories.filter { it.content.contains(query, ignoreCase = true) }
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "الذاكرة", onBack = onBack) {
                IconButton(onClick = { viewModel.clearAllMemories() }) {
                    Icon(Icons.Outlined.DeleteSweep, contentDescription = "مسح الكل", tint = SemanticError.copy(0.75f))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            NeuralSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = "بحث في الذاكرة...",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            // Stats row
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MemoryStat("الإجمالي", "${memories.size}", PrimaryAccent)
                MemoryStat("تمت تصفيته", "${filtered.size}", SecondaryAccent)
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Psychology, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                        Text("لا توجد ذكريات محفوظة", color = TextTertiary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { memory ->
                        MemoryCard(memory = memory, onDelete = { viewModel.deleteMemory(memory.id) })
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun MemoryStat(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(0.10f))
            .border(0.5.dp, color.copy(0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(label, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MemoryCard(
    memory: com.airi.assistant.memory.MemoryEntry,
    onDelete: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("d MMM HH:mm", Locale("ar")) }
    val dateStr = remember(memory.timestamp) { fmt.format(Date(memory.timestamp)) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryAccent.copy(0.12f))
                        .border(0.5.dp, PrimaryAccent.copy(0.25f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Memory, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(15.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    memory.content,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dateStr, color = TextTertiary, fontSize = 11.sp)
                if (!confirmDelete) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = "حذف",
                        tint = SemanticError.copy(0.6f),
                        modifier = Modifier.size(17.dp).clickable { confirmDelete = true }
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = { confirmDelete = false },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) { Text("إلغاء", color = TextSecondary, fontSize = 11.sp) }
                        TextButton(
                            onClick = onDelete,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            colors = ButtonDefaults.textButtonColors(contentColor = SemanticError)
                        ) { Text("تأكيد", fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}
