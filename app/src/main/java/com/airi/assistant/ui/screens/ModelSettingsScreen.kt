package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel

@Composable
fun ModelSettingsScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit
) {
    val execMode      by viewModel.executionMode.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val selectedModel = modelState.selectedModelId
    val localModels   = modelState.availableModels

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "النماذج والذكاء الاصطناعي", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Execution Mode ────────────────────────────────────────────
            NeuralSectionLabel("وضع التنفيذ")
            NeuralSectionCard {
                listOf(
                    Triple("LOCAL",  "محلي",  "يعمل بالكامل على الجهاز"),
                    Triple("CLOUD",  "سحابي", "يتصل بـ API خارجي"),
                    Triple("HYBRID", "هجين",  "يوازن بين المحلي والسحابي")
                ).forEachIndexed { idx, (mode, label, desc) ->
                    val selected = when (mode) {
                        "LOCAL"  -> execMode == ExecutionMode.LOCAL_ONLY
                        "CLOUD"  -> execMode == ExecutionMode.CLOUD_ONLY
                        else     -> execMode == ExecutionMode.HYBRID
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setExecutionMode(when (mode) { "LOCAL" -> ExecutionMode.LOCAL_ONLY; "CLOUD" -> ExecutionMode.CLOUD_ONLY; else -> ExecutionMode.HYBRID }) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        val modeColor = when (mode) {
                            "LOCAL"  -> AccentLocal
                            "CLOUD"  -> AccentCloud
                            else     -> AccentHybrid
                        }
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(modeColor.copy(0.14f))
                                .border(0.5.dp, modeColor.copy(0.3f), RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                when (mode) {
                                    "LOCAL"  -> Icons.Outlined.Memory
                                    "CLOUD"  -> Icons.Outlined.Cloud
                                    else     -> Icons.Outlined.Sync
                                },
                                contentDescription = null,
                                tint = modeColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(desc, color = TextSecondary, fontSize = 12.sp)
                        }
                        RadioButton(
                            selected = selected,
                            onClick  = { viewModel.setExecutionMode(when (mode) { "LOCAL" -> ExecutionMode.LOCAL_ONLY; "CLOUD" -> ExecutionMode.CLOUD_ONLY; else -> ExecutionMode.HYBRID }) },
                            colors   = RadioButtonDefaults.colors(selectedColor = PrimaryAccent, unselectedColor = TextTertiary)
                        )
                    }
                    if (idx < 2) NeuralDivider()
                }
            }

            // ── Local Models ──────────────────────────────────────────────
            if (localModels.isNotEmpty()) {
                NeuralSectionLabel("النماذج المتاحة")
                NeuralSectionCard {
                    localModels.forEachIndexed { idx, model ->
                        val isSelected = selectedModel == model.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectModel(model.id) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PrimaryAccent.copy(0.14f))
                                    .border(0.5.dp, if (isSelected) PrimaryAccent.copy(0.5f) else PrimaryAccent.copy(0.2f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Memory, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(18.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(model.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${model.paramCount} · ${model.quantization}", color = TextSecondary, fontSize = 12.sp)
                            }
                            if (isSelected) NeuralBadge("نشط", PrimaryAccent)
                            RadioButton(
                                selected = isSelected,
                                onClick  = { viewModel.selectModel(model.id) },
                                colors   = RadioButtonDefaults.colors(selectedColor = PrimaryAccent, unselectedColor = TextTertiary)
                            )
                        }
                        if (idx < localModels.size - 1) NeuralDivider()
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(Surface1).border(1.dp, BorderLight, RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.Memory, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(40.dp))
                        Text("لا توجد نماذج محلية", color = TextTertiary, fontSize = 14.sp)
                        Text("قم بتنزيل نموذج GGUF لبدء الاستخدام المحلي", color = TextTertiary, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
