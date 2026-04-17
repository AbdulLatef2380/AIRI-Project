package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.event.ExecutionHistoryStore
import com.airi.assistant.ui.theme.CosmicAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilityScreen(onBack: () -> Unit) {

    val historyStore = remember { ServiceLocator.executionHistoryStore }
    var entries by remember { mutableStateOf(historyStore.getRecentEntries(100)) }
    var filterType by remember { mutableStateOf("All") }

    val filterOptions = listOf("All", "AgentStarted", "AgentSuccess", "AgentFailed",
        "AgentTimeout", "Skill", "Tool", "Policy", "SignIn", "Sub", "Limit", "Premium")

    val displayed = if (filterType == "All") entries
    else entries.filter { it.eventType.startsWith(filterType) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.65f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Column {
                        Text("Observability", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 17.sp)
                        Text("${displayed.size} events", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        historyStore.clear()
                        entries = emptyList()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear history", tint = Color.White.copy(alpha = 0.6f))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Filter chips ───────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = filterOptions.indexOf(filterType).coerceAtLeast(0),
                containerColor   = Color.Black.copy(alpha = 0.5f),
                contentColor     = CosmicAccent,
                edgePadding      = 8.dp
            ) {
                filterOptions.forEach { type ->
                    Tab(
                        selected = filterType == type,
                        onClick  = { filterType = type },
                        text     = {
                            Text(
                                text     = type,
                                fontSize = 11.sp,
                                color    = if (filterType == type) CosmicAccent else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    )
                }
            }

            if (displayed.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No events recorded", color = Color.White.copy(alpha = 0.4f), fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayed, key = { it.timestamp.toString() + it.eventType }) { entry ->
                        EventEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventEntryRow(entry: ExecutionHistoryStore.HistoryEntry) {
    val (bgColor, textColor) = when {
        entry.success == true  -> Color(0xFF1B3A2D) to Color(0xFF4CAF50)
        entry.success == false -> Color(0xFF3A1B1B) to Color(0xFFEF5350)
        else                   -> Color(0xFF1E1E2E) to CosmicAccent
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = entry.eventType,
                        color      = textColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 12.sp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text     = entry.formattedTime,
                        color    = Color.White.copy(alpha = 0.4f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (entry.details.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text     = entry.details,
                        color    = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        maxLines = 2
                    )
                }
            }
            entry.success?.let { ok ->
                Spacer(Modifier.width(8.dp))
                Text(
                    text     = if (ok) "✓" else "✗",
                    color    = textColor,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
