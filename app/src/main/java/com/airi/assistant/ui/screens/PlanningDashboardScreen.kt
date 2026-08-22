package com.airi.assistant.ui.screens

import com.airi.assistant.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.plan.AgentPlanContent
import com.airi.assistant.ui.plan.AgentPlanViewModel
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent

/**
 * – Planning Dashboard Screen.
 * Reuses AgentPlanContent inside a full screen, plus history of past plans
 * from ExecutionHistoryStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanningDashboardScreen(
    onBack: () -> Unit
) {
    val planViewModel: AgentPlanViewModel = viewModel()
    val steps by planViewModel.steps.collectAsState()
    val history = remember {
        runCatching {
            com.airi.assistant.core.ServiceLocator.executionHistoryStore.getRecentEntries(20)
        }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.planning_dashboard_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                if (steps.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.planning_no_active_plan), fontSize = 16.sp, color = AiriTheme.onSurfaceVariant)
                        Text(stringResource(R.string.planning_empty_hint),
                            fontSize = 13.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                }
            } else {
                // Active plan
                Text(
                    "Active Plan",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = CosmicAccent
                )
                AgentPlanContent(
                    viewModel = planViewModel,
                    modifier  = Modifier.fillMaxWidth()
                )
                }
            }

            // Past plans from history store
            if (history.isNotEmpty()) {
                item {
                    Divider(modifier = Modifier.padding(vertical = 8.dp), color = AiriTheme.outline.copy(0.2f))
                    Text(
                        "Past Executions",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onSurfaceVariant
                    )
                }
                items(history) { entry ->
                        ListItem(
                            headlineContent = { Text(entry.eventType.take(80), fontSize = 13.sp) },
                            supportingContent = {
                                Text(
                                    java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp)),
                                    fontSize = 11.sp,
                                    color = AiriTheme.onSurfaceVariant
                                )
                            },
                            colors = ListItemDefaults.colors(containerColor = AiriTheme.background)
                        )
                        Divider(color = AiriTheme.outline.copy(0.1f))
                }
            }
        }
    }
}
