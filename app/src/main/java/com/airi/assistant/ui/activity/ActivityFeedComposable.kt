package com.airi.assistant.ui.activity

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ActivityFeedComposable(modifier: Modifier = Modifier, compactMaxItems: Int = 3) {
    val events by AgentActivityBus.recentEvents.collectAsStateWithLifecycle()
    var isExpanded by remember { mutableStateOf(false) }
    var categoryFilter by remember { mutableStateOf<ActivityCategory?>(null) }
    val displayEvents = remember(events, categoryFilter) {
        if (categoryFilter != null) events.filter { it.category == categoryFilter } else events
    }
    if (displayEvents.isEmpty()) return

    Column(modifier = modifier) {
        if (isExpanded) {
            ExpandedFeed(events = displayEvents, categoryFilter = categoryFilter,
                onFilterChange = { categoryFilter = it }, onCollapse = { isExpanded = false },
                onClear = { AgentActivityBus.clearHistory() })
        } else {
            CompactFeed(events = displayEvents.take(compactMaxItems), totalCount = displayEvents.size,
                onExpand = { isExpanded = true })
        }
    }
}

@Composable
private fun CompactFeed(events: List<ActivityEvent>, totalCount: Int, onExpand: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
        .background(Color(0xFF0B1120).copy(alpha = 0.92f))
        .clickable(onClick = onExpand)
        .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)) {
        events.forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(event.category.emoji, fontSize = 11.sp)
                Text(event.message.take(72), fontSize = 11.sp,
                    color = sevColor(event.severity).copy(alpha = 0.85f), maxLines = 1, modifier = Modifier.weight(1f))
                Text(fmtTime(event.timestampMs), fontSize = 10.sp, color = Color.White.copy(alpha = 0.25f))
            }
        }
        if (totalCount > events.size)
            Text("+${totalCount - events.size} more — tap to expand", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
    }
}

@Composable
private fun ExpandedFeed(events: List<ActivityEvent>, categoryFilter: ActivityCategory?,
    onFilterChange: (ActivityCategory?) -> Unit, onCollapse: () -> Unit, onClear: () -> Unit) {
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)
        .clip(shape).background(Color(0xFF0D1526).copy(alpha = 0.98f))
        .border(0.5.dp, Color.White.copy(alpha = 0.08f), shape)) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("Activity", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            Text("Clear", fontSize = 11.sp, color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.clickable(onClick = onClear).padding(horizontal = 8.dp))
            Text("⌄", fontSize = 14.sp, color = Color.White.copy(alpha = 0.5f), modifier = Modifier.clickable(onClick = onCollapse))
        }
        // Category chips
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", categoryFilter == null) { onFilterChange(null) }
            ActivityCategory.entries.take(6).forEach { cat ->
                Chip(cat.emoji, categoryFilter == cat) { onFilterChange(if (categoryFilter == cat) null else cat) }
            }
        }
        Divider(color = Color.White.copy(alpha = 0.07f))
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(items = events, key = { it.id }) { event ->
                var detailVisible by remember(event.id) { mutableStateOf(false) }
                val hasDetail = !event.detail.isNullOrBlank()
                Column(modifier = Modifier.fillMaxWidth()
                    .then(if (hasDetail) Modifier.clickable { detailVisible = !detailVisible } else Modifier)
                    .padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(event.category.emoji, fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.message, fontSize = 12.sp, color = sevColor(event.severity), lineHeight = 17.sp)
                            Text("${event.category.label} · ${fmtTime(event.timestampMs)}", fontSize = 10.sp, color = Color.White.copy(alpha = 0.3f))
                        }
                        if (event.severity != ActivitySeverity.INFO)
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(sevColor(event.severity)).padding(top = 4.dp))
                    }
                    AnimatedVisibility(visible = detailVisible && hasDetail) {
                        Text(event.detail ?: "", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f), lineHeight = 16.sp,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, start = 21.dp)
                                .clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.04f)).padding(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center,
        modifier = Modifier.clip(CircleShape)
            .background(if (selected) CosmicAccent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.06f))
            .border(0.5.dp, if (selected) CosmicAccent.copy(alpha = 0.50f) else Color.Transparent, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, fontSize = 11.sp, color = if (selected) CosmicAccent else Color.White.copy(alpha = 0.5f))
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))
private fun sevColor(sev: ActivitySeverity) = when (sev) {
    ActivitySeverity.INFO  -> Color.White.copy(alpha = 0.78f)
    ActivitySeverity.WARN  -> SemanticWarn
    ActivitySeverity.ERROR -> SemanticError
}
