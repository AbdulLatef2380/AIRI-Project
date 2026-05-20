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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.DividerColor
import com.airi.assistant.ui.theme.SurfaceCard
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: ConnectorsViewModel = viewModel(),
    onBack: () -> Unit
) {
    val allItems    by viewModel.items.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    val connected    = allItems.filter { it.state.connected }
    val disconnected = allItems.filter { !it.state.connected }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CosmicBlack.copy(alpha = 0.92f)),
                navigationIcon = {
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = CosmicAccent)
                    }
                },
                title = {
                    Text(
                        text = "الموصلات",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                },
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            if (connected.isNotEmpty()) {
                item {
                    ConnectorGroup {
                        connected.forEachIndexed { idx, row ->
                            ConnectorToggleRow(
                                name     = row.meta.name,
                                subLabel = row.meta.type.uiLabel,
                                icon     = row.meta.type.uiIcon,
                                iconBg   = row.meta.type.uiColor,
                                checked  = true,
                                onToggle = { viewModel.disconnect(row.meta.id) }
                            )
                            if (idx < connected.lastIndex)
                                Divider(color = DividerColor, modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                }
            }

            if (disconnected.isNotEmpty()) {
                item {
                    Text(
                        "متاحة للاتصال",
                        color = Color.White.copy(alpha = 0.40f),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        textAlign = TextAlign.End
                    )
                }
                item {
                    ConnectorGroup {
                        disconnected.forEachIndexed { idx, row ->
                            ConnectorConnectRow(
                                name      = row.meta.name,
                                subLabel  = row.meta.type.uiLabel,
                                icon      = row.meta.type.uiIcon,
                                iconBg    = row.meta.type.uiColor,
                                onConnect = { viewModel.connect(row.meta.id) }
                            )
                            if (idx < disconnected.lastIndex)
                                Divider(color = DividerColor, modifier = Modifier.padding(start = 64.dp))
                        }
                    }
                }
            }

            if (allItems.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Hub, null, tint = Color.White.copy(alpha = 0.25f), modifier = Modifier.size(52.dp))
                            Spacer(Modifier.height(14.dp))
                            Text("لا توجد موصلات مضافة", color = Color.White.copy(alpha = 0.35f), fontSize = 15.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = { showAddSheet = true },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("إضافة موصل", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddConnectorSheet(onDismiss = { showAddSheet = false })
    }
}

@Composable
private fun ConnectorGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SurfaceCard), content = content)
}

@Composable
private fun ConnectorToggleRow(
    name: String, subLabel: String, icon: ImageVector, iconBg: Color,
    checked: Boolean, onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Switch(
            checked = checked, onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = CosmicAccent,
                uncheckedThumbColor = Color.White.copy(0.6f), uncheckedTrackColor = Color.White.copy(0.15f)
            )
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(subLabel, color = Color.White.copy(0.45f), fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconBg.copy(0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconBg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ConnectorConnectRow(
    name: String, subLabel: String, icon: ImageVector, iconBg: Color, onConnect: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Button(
            onClick = onConnect, shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Text("اتصال", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.End) {
                Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(subLabel, color = Color.White.copy(0.45f), fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(iconBg.copy(0.18f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = iconBg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddConnectorSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = sheetState, containerColor = Color(0xFF111525),
        dragHandle = {
            Box(Modifier.padding(vertical = 10.dp)) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(0.25f)))
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("إضافة موصل", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), textAlign = TextAlign.End)
            AddSheetOption("تطبيقات الموصل", "اختر من قائمة التطبيقات المتاحة", onDismiss)
            Spacer(Modifier.height(10.dp))
            AddSheetOption("API مخصص", "أضف موصلًا باستخدام API مخصص", onDismiss)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun AddSheetOption(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF1E2438))
            .clickable(onClick = onClick).padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.ChevronRight, null, tint = Color.White.copy(0.30f), modifier = Modifier.size(20.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(0.45f), fontSize = 13.sp)
        }
    }
}

// ── ConnectorType extensions ────────────────────────────────────────────────
private val ConnectorType.uiLabel: String get() = when (this) {
    ConnectorType.API    -> "سحابي"
    ConnectorType.APP    -> "تطبيق"
    ConnectorType.LOCAL  -> "على الجهاز"
    ConnectorType.MCP    -> "MCP"
    ConnectorType.SYSTEM -> "النظام"
}

private val ConnectorType.uiIcon: ImageVector get() = when (this) {
    ConnectorType.API    -> Icons.Outlined.Cloud
    ConnectorType.APP    -> Icons.Outlined.Extension
    ConnectorType.LOCAL  -> Icons.Outlined.Memory
    ConnectorType.MCP    -> Icons.Outlined.Hub
    ConnectorType.SYSTEM -> Icons.Outlined.Settings
}

private val ConnectorType.uiColor: Color get() = when (this) {
    ConnectorType.API    -> Color(0xFF7C6FF0)
    ConnectorType.APP    -> Color(0xFF4FC3F7)
    ConnectorType.LOCAL  -> Color(0xFF66BB6A)
    ConnectorType.MCP    -> Color(0xFFFFB74D)
    ConnectorType.SYSTEM -> Color(0xFF9E9E9E)
}
