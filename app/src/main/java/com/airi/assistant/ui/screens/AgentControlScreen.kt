package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.viewmodel.AgentViewModel
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentControlScreen(
    viewModel: AgentViewModel,
    onBack: () -> Unit,
    onNavigate: (String) -> Unit = {}
) {
    val skillInfos  = remember { viewModel.getSkillInfos().toMutableStateList() }
    val toolList    = remember { viewModel.getToolList() }
    val debugMode   by viewModel.debugMode.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.65f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        "Agent Control",
                        fontWeight = FontWeight.Bold,
                        color = AiriTheme.onBackground
                    )
                },
                actions = {
                    // Planning Dashboard shortcut
                    IconButton(onClick = { onNavigate(com.airi.assistant.ui.AiriRoute.PLANNING_DASHBOARD) }) {
                        Icon(
                            Icons.Outlined.Timeline,
                            contentDescription = "Planning Dashboard",
                            tint = com.airi.assistant.ui.theme.CosmicAccent
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AgentControlCard {
                AgentSectionHeader(icon = Icons.Outlined.AutoAwesome, title = "Skills")
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Toggle skills on/off. Skills require connected integrations.",
                    fontSize = 11.sp,
                    color = AiriTheme.outline,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                skillInfos.forEachIndexed { index, info ->
                    if (index > 0) {
                        Divider(
                            color = AiriTheme.outline.copy(alpha = 0.2f),
                            modifier = Modifier.padding(vertical = 6.dp)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = controlSkillDisplayName(info.name),
                                    color = if (info.isConnected) AiriTheme.onSurface.copy(alpha = 0.9f)
                                            else AiriTheme.onSurface.copy(alpha = 0.35f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    shape = AIRIShapes.sm,
                                    color = if (info.isConnected)
                                                Color(0xFF00C853).copy(alpha = 0.15f)
                                            else Color(0xFFFF5252).copy(alpha = 0.12f)
                                ) {
                                    Text(
                                        text = if (info.isConnected) "Connected" else "Not Connected",
                                        fontSize = 9.sp,
                                        color = if (info.isConnected) Color(0xFF00C853)
                                                else Color(0xFFFF5252),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = info.description,
                                color = AiriTheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = info.isEnabled && info.isConnected,
                            onCheckedChange = { enabled ->
                                if (info.isConnected) {
                                    skillInfos[index] = info.copy(isEnabled = enabled)
                                    viewModel.setSkillEnabled(info.name, enabled)
                                }
                            },
                            enabled = info.isConnected,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor   = CosmicAccent,
                                checkedTrackColor   = CosmicAccent.copy(alpha = 0.3f),
                                uncheckedThumbColor = AiriTheme.onSurface.copy(alpha = 0.4f),
                                uncheckedTrackColor = AiriTheme.onSurface.copy(alpha = 0.1f),
                                disabledCheckedThumbColor   = AiriTheme.onSurface.copy(alpha = 0.2f),
                                disabledUncheckedThumbColor = AiriTheme.onSurface.copy(alpha = 0.15f),
                                disabledCheckedTrackColor   = AiriTheme.outline,
                                disabledUncheckedTrackColor = AiriTheme.onSurface.copy(alpha = 0.05f)
                            )
                        )
                    }
                }
            }
            AgentControlCard {
                AgentSectionHeader(icon = Icons.Outlined.Build, title = "Available Tools")
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Tools are the low-level actions that skills use to call external services.",
                    fontSize = 11.sp,
                    color = AiriTheme.outline,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                toolList.forEachIndexed { index, (toolName, source) ->
                    if (index > 0) Divider(
                        color = AiriTheme.outline.copy(alpha = 0.04f),
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = toolName.replace("_", " "),
                                color = AiriTheme.onBackground.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Surface(
                            shape = AIRIShapes.xs,
                            color = CosmicAccent.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = source,
                                fontSize = 10.sp,
                                color = CosmicAccent.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
            AgentControlCard {
                AgentSectionHeader(icon = Icons.Outlined.BugReport, title = "Debug Mode")
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Shows raw tool JSON and reasoning steps in the chat trace.",
                    fontSize = 11.sp,
                    color = AiriTheme.outline,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Debug Mode",
                            color = AiriTheme.onBackground.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (debugMode) "Debug info visible in traces" else "Only results shown",
                            color = if (debugMode) CosmicAccent.copy(alpha = 0.65f)
                                    else AiriTheme.onSurface.copy(alpha = 0.35f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = debugMode,
                        onCheckedChange = { viewModel.setDebugMode(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor   = CosmicAccent,
                            checkedTrackColor   = CosmicAccent.copy(alpha = 0.3f),
                            uncheckedThumbColor = AiriTheme.onSurface.copy(alpha = 0.4f),
                            uncheckedTrackColor = AiriTheme.onSurface.copy(alpha = 0.1f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AgentControlCard(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(AIRIShapes.lg)
            .background(AiriTheme.onSurface.copy(alpha = 0.05f))
            .border(1.dp, AiriTheme.outline, AIRIShapes.lg)
            .padding(16.dp)
    ) {
        Column(content = content)
    }
}

@Composable
private fun AgentSectionHeader(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontWeight = FontWeight.Bold, color = CosmicAccent, fontSize = 13.sp)
    }
}

private fun controlSkillDisplayName(name: String): String = when (name) {
    "github_guardian"    -> "GitHub Guardian"
    "telegram_messenger" -> "Telegram Messenger"
    "gmail_assistant"    -> "Gmail Assistant"
    "drive_search"       -> "Drive Search"
    "calendar_events"    -> "Calendar Events"
    else -> name.replace("_", " ").split(" ").joinToString(" ") { it.replaceFirstChar(Char::titlecase) }
}
