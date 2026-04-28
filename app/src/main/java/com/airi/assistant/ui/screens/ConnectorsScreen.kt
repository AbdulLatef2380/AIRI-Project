package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel

/**
 * Connectors screen — replaces the legacy IntegrationsScreen UI shape
 * with a tabbed view (API / Apps / MCP / System) over the new
 * [com.airi.assistant.connector.Connector] layer.
 *
 * The legacy IntegrationsScreen continues to exist for backwards
 * compatibility with deep-links; users can be migrated incrementally.
 *
 * Iconography: type-derived Material vector icons (no static drawable
 * assets, no network favicons). The connector's [ConnectorMeta.iconUrl]
 * is reserved for a future Coil-backed favicon loader.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    onBack: () -> Unit,
    viewModel: ConnectorsViewModel = viewModel(),
) {
    val items by viewModel.items.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text("Connectors", fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ConnectorTabRow(
                selected = selectedTab,
                onSelect = viewModel::selectTab,
            )

            val visible = items.filter { it.meta.type == selectedTab }

            if (visible.isEmpty()) {
                EmptyState(selectedTab)
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items = visible, key = { it.meta.id }) { row ->
                        ConnectorCard(
                            row = row,
                            onConnect = { viewModel.connect(row.meta.id) },
                            onDisconnect = { viewModel.disconnect(row.meta.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectorTabRow(
    selected: ConnectorType,
    onSelect: (ConnectorType) -> Unit,
) {
    val tabs = listOf(
        ConnectorType.API    to "API",
        ConnectorType.APP    to "Apps",
        ConnectorType.MCP    to "MCP",
        ConnectorType.SYSTEM to "System",
    )
    // Intentionally NOT exposing LOCAL as its own tab — local
    // capabilities (intents, voice, files) are surfaced under SYSTEM
    // for the user-visible grouping; the routing layer still
    // distinguishes them internally.
    val selectedIndex = tabs.indexOfFirst { it.first == selected }
        .coerceAtLeast(0)
    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor = Color.Transparent,
    ) {
        tabs.forEachIndexed { index, (type, label) ->
            Tab(
                selected = index == selectedIndex,
                onClick = { onSelect(type) },
                text = { Text(label) },
            )
        }
    }
}

@Composable
private fun ConnectorCard(
    row: ConnectorsViewModel.ConnectorRow,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(16.dp),
            )
            .clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectorIconBadge(row.meta.type)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.meta.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                    Text(
                        text = row.meta.description,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                    )
                }
                if (row.state.connected) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Connected",
                        tint = Color(0xFF4CAF50),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = row.state.statusLine.ifBlank {
                    if (row.state.connected) "Connected" else "Not connected"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            )
            row.state.errorMessage?.let { err ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = err,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.state.connected) {
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                } else {
                    Button(
                        onClick = onConnect,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun ConnectorIconBadge(type: ConnectorType) {
    val (icon: ImageVector, tint: Color) = when (type) {
        ConnectorType.API    -> Icons.Filled.Cloud           to Color(0xFF42A5F5)
        ConnectorType.APP    -> Icons.Filled.Apps            to Color(0xFFAB47BC)
        ConnectorType.MCP    -> Icons.Filled.Extension       to Color(0xFFFFB300)
        ConnectorType.SYSTEM -> Icons.Filled.PhoneAndroid    to Color(0xFF66BB6A)
        ConnectorType.LOCAL  -> Icons.Filled.DeveloperBoard  to Color(0xFFEC407A)
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun EmptyState(type: ConnectorType) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = when (type) {
                ConnectorType.API    -> "No API connectors registered yet."
                ConnectorType.APP    -> "No app connectors registered yet."
                ConnectorType.MCP    -> "No MCP servers configured."
                ConnectorType.SYSTEM -> "No system connectors registered yet."
                ConnectorType.LOCAL  -> "No local connectors registered yet."
            },
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
