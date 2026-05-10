import com.airi.assistant.ui.components.AiriScreenHeader
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
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ConnectorsViewModel

@Composable
fun ConnectorsScreen(
    onBack: () -> Unit,
    viewModel: ConnectorsViewModel = viewModel()
) {
    val items       by viewModel.items.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    val tabs = listOf(
        ConnectorType.API    to "API",
        ConnectorType.APP    to "تطبيقات",
        ConnectorType.MCP    to "MCP",
        ConnectorType.SYSTEM to "النظام"
    )

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "الموصلات", onBack = onBack) {
                IconButton(onClick = { viewModel.selectTab(ConnectorType.API) }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = PrimaryAccent)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Tab row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { (type, label) ->
                    val selected = selectedTab == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) PrimaryAccent else Surface2)
                            .border(1.dp, if (selected) PrimaryAccent else BorderLight, RoundedCornerShape(12.dp))
                            .clickable { viewModel.selectTab(type) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }

            val visible = items.filter { it.meta.type == selectedTab }

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.Extension, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                        Text("لا توجد موصلات مسجّلة", color = TextTertiary, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visible, key = { it.meta.connectorId }) { item ->
                        ConnectorCard(
                            item    = item,
                            onToggle = { viewModel.toggle(item.meta.connectorId) },
                            onConfigure = { viewModel.configure(item.meta.connectorId) }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectorCard(
    item: com.airi.assistant.ui.viewmodel.ConnectorUiItem,
    onToggle: () -> Unit,
    onConfigure: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = if (item.isEnabled) SemanticSuccess else TextTertiary
    val typeColor = when (item.meta.type) {
        ConnectorType.API    -> PrimaryAccent
        ConnectorType.APP    -> SecondaryAccent
        ConnectorType.MCP    -> SemanticWarning
        ConnectorType.SYSTEM -> AccentHybrid
        else                 -> TextTertiary
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(
                width = 1.dp,
                color = if (item.isEnabled) PrimaryAccent.copy(0.25f) else BorderLight,
                shape = RoundedCornerShape(16.dp)
            )
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(typeColor.copy(0.14f))
                        .border(0.5.dp, typeColor.copy(0.3f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = typeColor, modifier = Modifier.size(20.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.meta.displayName, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!item.meta.description.isNullOrBlank()) {
                        Text(item.meta.description!!, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                NeuralToggle(checked = item.isEnabled, onCheckedChange = { onToggle() })
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    NeuralDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeuralBadge(item.meta.type.name, typeColor)
                        Spacer(Modifier.weight(1f))
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
