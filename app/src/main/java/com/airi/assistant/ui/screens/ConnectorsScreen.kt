package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.SurfaceCard
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.DividerColor
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel

// ── Tab definition ─────────────────────────────────────────────────────────────
private data class ConnectorTab(
    val type: ConnectorType,
    val label: String,
    val icon: ImageVector
)

private val TABS = listOf(
    ConnectorTab(ConnectorType.API,    "API",    Icons.Outlined.Cloud),
    ConnectorTab(ConnectorType.APP,    "تطبيقات", Icons.Outlined.Apps),
    ConnectorTab(ConnectorType.LOCAL,  "الجهاز", Icons.Outlined.PhoneAndroid),
    ConnectorTab(ConnectorType.MCP,    "MCP",    Icons.Outlined.Extension),
    ConnectorTab(ConnectorType.SYSTEM, "نظام",   Icons.Outlined.SettingsSuggest),
)

// ── Icon mapping ───────────────────────────────────────────────────────────────
private fun iconForId(id: String): ImageVector = when {
    id.contains("llm")       -> Icons.Outlined.SmartToy
    id.contains("intent")    -> Icons.Outlined.Android
    id.contains("voice")     -> Icons.Outlined.Mic
    id.contains("clipboard") -> Icons.Outlined.ContentCopy
    id.contains("apps")      -> Icons.Outlined.Apps
    id.contains("contacts")  -> Icons.Outlined.Contacts
    id.contains("system")    -> Icons.Outlined.Memory
    id.contains("mcp")       -> Icons.Outlined.Extension
    id.contains("github")    -> Icons.Outlined.Code
    id.contains("telegram")  -> Icons.Outlined.Send
    id.contains("notion")    -> Icons.Outlined.Description
    else                     -> Icons.Outlined.Hub
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: ConnectorsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val allItems     by viewModel.items.collectAsState()
    val selectedTab  by viewModel.selectedTab.collectAsState()

    val visibleItems = allItems.filter { it.meta.type == selectedTab }
    val connectedCount = allItems.count { it.state.connected }

    Scaffold(
        containerColor = CosmicBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CosmicBlack.copy(alpha = 0.95f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "الموصلات",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        if (connectedCount > 0) {
                            Text(
                                text = "$connectedCount متصل",
                                color = SemanticSuccess,
                                fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    // Add-connector affordance is hidden until custom MCP endpoint
                    // creation is implemented end-to-end. A non-functional "+"
                    // button on a top-bar is a confirmable dead-end (Bug class:
                    // UI lie). Re-enable this slot when ConnectorsViewModel
                    // exposes addCustomConnector() and a corresponding sheet
                    // is available.
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Tab row ────────────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = TABS.indexOfFirst { it.type == selectedTab }.coerceAtLeast(0),
                containerColor   = CosmicBlack,
                contentColor     = CosmicAccent,
                edgePadding      = 12.dp,
                divider          = { Divider(color = DividerColor) }
            ) {
                TABS.forEach { tab ->
                    val isSelected = tab.type == selectedTab
                    val tabCount   = allItems.count { it.meta.type == tab.type }
                    Tab(
                        selected = isSelected,
                        onClick  = { viewModel.selectTab(tab.type) },
                        text = {
                            Row(
                                verticalAlignment      = Alignment.CenterVertically,
                                horizontalArrangement  = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    tab.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSelected) CosmicAccent else Color.White.copy(0.45f)
                                )
                                Text(
                                    tab.label,
                                    fontSize = 12.sp,
                                    color = if (isSelected) CosmicAccent else Color.White.copy(0.45f)
                                )
                                if (tabCount > 0) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) CosmicAccent.copy(0.25f)
                                                else Color.White.copy(0.08f)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "$tabCount",
                                            fontSize = 9.sp,
                                            color = if (isSelected) CosmicAccent else Color.White.copy(0.45f),
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
            }

            // ── Connector list ─────────────────────────────────────────────────
            if (visibleItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = Color.White.copy(0.25f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "لا توجد موصلات في هذه الفئة",
                            color = Color.White.copy(0.35f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    items(visibleItems, key = { it.meta.id }) { row ->
                        ConnectorCard(
                            row       = row,
                            onConnect    = { viewModel.connect(row.meta.id) },
                            onDisconnect = { viewModel.disconnect(row.meta.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorCard(
    row: ConnectorsViewModel.ConnectorRow,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val isConnected = row.state.connected
    val statusColor = if (isConnected) SemanticSuccess else Color.White.copy(0.30f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceCard)
            .border(
                width = 1.dp,
                color = if (isConnected) SemanticSuccess.copy(0.25f) else Color.White.copy(0.07f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Status dot + icon + name
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Status indicator dot
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    // Icon tile
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CosmicAccent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            iconForId(row.meta.id),
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            row.meta.name,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isConnected) "متصل" else "غير متصل",
                            color = statusColor,
                            fontSize = 11.sp
                        )
                    }
                }
                // Connect / Disconnect toggle
                Switch(
                    checked  = isConnected,
                    onCheckedChange = { if (it) onConnect() else onDisconnect() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor       = Color.White,
                        checkedTrackColor       = SemanticSuccess,
                        uncheckedThumbColor     = Color.White.copy(0.6f),
                        uncheckedTrackColor     = SurfaceRaised
                    )
                )
            }

            // Expandable description
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Divider(color = Color.White.copy(0.07f))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        row.meta.description,
                        color    = Color.White.copy(0.55f),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    if (row.state.errorMessage != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint = SemanticError,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                row.state.errorMessage,
                                color    = SemanticError,
                                fontSize = 12.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
