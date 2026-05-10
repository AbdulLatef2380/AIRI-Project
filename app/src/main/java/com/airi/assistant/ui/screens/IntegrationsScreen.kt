package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.IntegrationsViewModel

@Composable
fun IntegrationsScreen(
    onBack: () -> Unit,
    viewModel: IntegrationsViewModel = viewModel()
) {
    val items by viewModel.items.collectAsState()

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "التكاملات", onBack = onBack) {
                IconButton(onClick = { /* add custom integration */ }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = PrimaryAccent)
                }
            }
        }
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Hub, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(52.dp))
                    Text("لا توجد تكاملات بعد", color = TextTertiary, fontSize = 14.sp)
                    Text("تكاملات Gmail وGitHub والمزيد قريباً", color = TextTertiary, fontSize = 12.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { integration ->
                    IntegrationCard(
                        integration = integration,
                        onToggle    = { viewModel.toggle(integration.id) },
                        onConfigure = { viewModel.configure(integration.id) }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun IntegrationCard(
    integration: IntegrationsViewModel.IntegrationItem,
    onToggle: () -> Unit,
    onConfigure: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val accentColor = when {
        integration.name.contains("Gmail", ignoreCase = true)  -> Color(0xFFEA4335)
        integration.name.contains("GitHub", ignoreCase = true) -> Color(0xFFF0F0F0)
        integration.name.contains("Telegram", ignoreCase = true) -> Color(0xFF2CA5E0)
        integration.name.contains("Notion", ignoreCase = true)  -> Color(0xFFFFFFFF)
        integration.name.contains("Drive", ignoreCase = true)   -> Color(0xFFFBBC04)
        else -> PrimaryAccent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, if (integration.isConnected) accentColor.copy(0.3f) else BorderLight, RoundedCornerShape(16.dp))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(accentColor.copy(alpha = 0.14f))
                        .border(0.5.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = accentColor, modifier = Modifier.size(22.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(integration.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(integration.description, color = TextSecondary, fontSize = 12.sp, maxLines = 2, lineHeight = 17.sp, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(4.dp))
                NeuralToggle(checked = integration.isConnected, onCheckedChange = { onToggle() })
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    NeuralDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeuralBadge(if (integration.isConnected) "متصل" else "غير متصل", if (integration.isConnected) SemanticSuccess else TextTertiary)
                        TextButton(onClick = onConfigure, colors = ButtonDefaults.textButtonColors(contentColor = PrimaryAccent)) {
                            Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("إعداد", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
