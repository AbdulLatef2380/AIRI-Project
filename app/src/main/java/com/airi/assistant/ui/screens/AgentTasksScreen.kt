package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.DividerColor
import com.airi.assistant.ui.theme.SurfaceCard

/**
 * Scheduled tasks screen — shown on the Schedule tab of bottom navigation.
 * Displays scheduled agent tasks with scheduled/completed filter tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentTasksScreen(
    onBack: () -> Unit,
    onNavigateToAgentControl: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(0) }  // 0 = Scheduled, 1 = Completed
    var showAddDialog by remember { mutableStateOf(false) }

    // Sample scheduled tasks — in production these come from the AgentWorker / persistence layer
    val scheduledTasks = remember {
        listOf(
            ScheduledTaskUiModel(
                id       = "1",
                title    = "تحليل تقرير المبيعات",
                schedule = "يوميًا، 9:00 ص",
                isActive = true
            ),
            ScheduledTaskUiModel(
                id       = "2",
                title    = "مراجعة البريد الإلكتروني",
                schedule = "كل ساعة",
                isActive = true
            )
        )
    }

    val completedTasks = remember { listOf<ScheduledTaskUiModel>() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicBlack.copy(alpha = 0.92f)),
                navigationIcon = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add task", tint = CosmicAccent)
                    }
                },
                title = {
                    Text(
                        text = "المهام",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
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
            // ── Filter tabs ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceCard),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TaskTab(
                    label     = "مجدول",
                    isSelected = selectedTab == 0,
                    modifier  = Modifier.weight(1f),
                    onClick   = { selectedTab = 0 }
                )
                TaskTab(
                    label     = "مكتمل",
                    isSelected = selectedTab == 1,
                    modifier  = Modifier.weight(1f),
                    onClick   = { selectedTab = 1 }
                )
            }

            val tasks = if (selectedTab == 0) scheduledTasks else completedTasks

            if (tasks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.History,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            text = if (selectedTab == 0) "لا توجد مهام مجدولة" else "لا توجد مهام مكتملة",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskItem(task = task)
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTaskDialog(onDismiss = { showAddDialog = false })
    }
}

@Composable
private fun TaskTab(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .padding(4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) CosmicAccent else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            color      = if (isSelected) Color.White else Color.White.copy(alpha = 0.50f),
            fontSize   = 14.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun TaskItem(task: ScheduledTaskUiModel) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Options icon on left (RTL start)
        Icon(
            Icons.Outlined.MoreVert,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.35f),
            modifier = Modifier.size(18.dp)
        )

        // Task info on right (RTL end)
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = task.title,
                    color      = Color.White,
                    fontSize   = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (task.isActive) CosmicAccent else Color.White.copy(alpha = 0.25f))
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text  = task.schedule,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 13.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskDialog(onDismiss: () -> Unit) {
    var taskName by remember { mutableStateOf("") }
    var scheduleText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor    = Color(0xFF131728),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "مهمة جديدة",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = taskName,
                    onValueChange = { taskName = it },
                    placeholder = { Text("اسم المهمة", color = Color.White.copy(alpha = 0.35f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    ),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End)
                )
                OutlinedTextField(
                    value = scheduleText,
                    onValueChange = { scheduleText = it },
                    placeholder = { Text("الجدول الزمني (مثال: يوميًا، 9:00 ص)", color = Color.White.copy(alpha = 0.35f)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
                    ),
                    textStyle = LocalTextStyle.current.copy(textAlign = TextAlign.End)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White),
                shape  = RoundedCornerShape(12.dp)
            ) {
                Text("إضافة")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("إلغاء", color = Color.White.copy(alpha = 0.6f))
            }
        }
    )
}

data class ScheduledTaskUiModel(
    val id: String,
    val title: String,
    val schedule: String,
    val isActive: Boolean
)
