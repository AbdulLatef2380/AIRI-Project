package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.airi.assistant.ui.components.NeuralScreenHeader
import com.airi.assistant.ui.components.NeuralToggle
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel

/**
 * ConnectorsScreen — tabbed view (API / Apps / MCP / System) over the
 * [com.airi.assistant.connector.Connector] layer.
 * Design updated to match Neural Violet design system.
 * All ViewModel wiring is unchanged.
 */
@Composable
fun ConnectorsScreen(
    onBack:    () -> Unit,
    viewModel: ConnectorsViewModel = viewModel(),
) {
    val items       by viewModel.items.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0)
    ) {
        NeuralScreenHeader(title = "الموصلات", onBack = onBack)

        ConnectorTabRow(selected = selectedTab, onSelect = viewModel::selectTab)

        val visible = items.filter { it.meta.type == selectedTab }

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.Extension,
                        contentDescription = null,
                        tint     = TextTertiary,
                        modifier = Modifier.size(40.dp)
                    )
                    Text(
                        text = when (selectedTab) {
                            ConnectorType.API    -> "لا توجد موصلات API مسجّلة بعد."
                            ConnectorType.APP    -> "لا توجد موصلات تطبيقات مسجّلة بعد."
                            ConnectorType.MCP    -> "لا توجد خوادم MCP مُعدَّة."
                            ConnectorType.SYSTEM -> "لا توجد موصلات نظام مسجّلة بعد."
                            ConnectorType.LOCAL  -> "لا توجد موصلات محلية مسجّلة بعد."
                        },
                        color    = TextTertiary,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier        = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding  = PaddingValues(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(items = visible, key = { it.meta.id }) { row ->
                    ConnectorCard(
                        row         = row,
                        onConnect   = { viewModel.connect(row.meta.id) },
                        onDisconnect = { viewModel.disconnect(row.meta.id) },
                    )
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
        ConnectorType.APP    to "تطبيقات",
        ConnectorType.MCP    to "MCP",
        ConnectorType.SYSTEM to "نظام",
    )
    val selectedIndex = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)

    TabRow(
        selectedTabIndex = selectedIndex,
        containerColor   = Surface0,
        contentColor     = PrimaryAccent,
        indicator        = { tabPositions ->
            Box(
                Modifier
                    .tabIndicatorOffset(tabPositions[selectedIndex])
                    .height(2.dp)
                    .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    .background(PrimaryAccent)
            )
        },
        divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(BorderLight)) }
    ) {
        tabs.forEachIndexed { index, (type, label) ->
            val active = index == selectedIndex
            Tab(
                selected = active,
                onClick  = { onSelect(type) },
                text = {
                    Text(
                        label,
                        color      = if (active) PrimaryAccent else TextTertiary,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize   = 13.sp
                    )
                }
            )
        }
    }
}

@Composable
private fun ConnectorCard(
    row:         ConnectorsViewModel.ConnectorRow,
    onConnect:   () -> Unit,
    onDisconnect: () -> Unit,
) {
    val (iconVec, iconColor) = connectorIconAndColor(row.meta.type)
    val isConnected = row.state.connected

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(
                width = 1.dp,
                color = if (isConnected) SemanticSuccess.copy(alpha = 0.40f) else BorderLight,
                shape = RoundedCornerShape(14.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Icon badge
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(iconColor.copy(alpha = 0.12f))
                    .border(1.dp, iconColor.copy(alpha = 0.22f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(iconVec, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(row.meta.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text(row.meta.description, color = TextSecondary, fontSize = 12.sp, maxLines = 1)
            }

            // Connected status dot
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(SemanticSuccess)
                )
                Spacer(Modifier.width(8.dp))
            }

            // Toggle
            NeuralToggle(
                checked         = isConnected,
                onCheckedChange = { if (it) onConnect() else onDisconnect() }
            )
        }

        // Status line
        val statusText = row.state.statusLine.ifBlank {
            if (isConnected) "متصل" else "غير متصل"
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text     = statusText,
            fontSize = 12.sp,
            color    = if (isConnected) SemanticSuccess else TextTertiary
        )

        // Error message
        row.state.errorMessage?.let { err ->
            Spacer(Modifier.height(4.dp))
            Text(text = err, fontSize = 11.sp, color = SemanticError)
        }

        // Action buttons
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SemanticError.copy(alpha = 0.12f))
                        .border(1.dp, SemanticError.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                ) {
                    TextButton(onClick = onDisconnect) {
                        Text("قطع الاتصال", color = SemanticError, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PrimaryAccent.copy(alpha = 0.12f))
                        .border(1.dp, PrimaryAccent.copy(alpha = 0.30f), RoundedCornerShape(8.dp))
                ) {
                    TextButton(onClick = onConnect) {
                        Text("اتصال", color = PrimaryAccent, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

private fun connectorIconAndColor(type: ConnectorType): Pair<ImageVector, Color> = when (type) {
    ConnectorType.API    -> Icons.Filled.Cloud          to Color(0xFF60A5FA)
    ConnectorType.APP    -> Icons.Filled.Apps           to Color(0xFFAB47BC)
    ConnectorType.MCP    -> Icons.Filled.Extension      to Color(0xFFFFB300)
    ConnectorType.SYSTEM -> Icons.Filled.PhoneAndroid   to Color(0xFF4CAF50)
    ConnectorType.LOCAL  -> Icons.Filled.DeveloperBoard to Color(0xFFEC407A)
}
