package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel

@Composable
fun AIModelsSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val openAiKey      by viewModel.openAiApiKey.collectAsState()
    val anthropicKey   by viewModel.anthropicApiKey.collectAsState()
    val geminiKey      by viewModel.geminiApiKey.collectAsState()
    val execMode       by viewModel.executionMode.collectAsState()

    var showOaiKey  by remember { mutableStateOf(false) }
    var showAntKey  by remember { mutableStateOf(false) }
    var showGemKey  by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "النماذج والذكاء الاصطناعي", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Execution mode quick-select
            NeuralSectionLabel("وضع التنفيذ")
            NeuralSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("LOCAL" to "محلي", "CLOUD" to "سحابي", "HYBRID" to "هجين").forEach { (mode, label) ->
                        val sel = execMode == mode
                        val col = when (mode) { "LOCAL" -> AccentLocal; "CLOUD" -> AccentCloud; else -> AccentHybrid }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) col.copy(0.18f) else Surface2)
                                .border(1.dp, if (sel) col.copy(0.55f) else BorderLight, RoundedCornerShape(10.dp))
                                .clickable { viewModel.setExecutionMode(mode) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (sel) col else TextSecondary, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }

            // Model picker shortcut
            NeuralSectionCard {
                NeuralRowItem(
                    icon = Icons.Outlined.Memory,
                    title = "اختيار النموذج",
                    subtitle = "النماذج المحلية والمتاحة",
                    iconTint = PrimaryAccent,
                    iconBgColor = PrimaryAccent.copy(0.14f),
                    onClick = { onNavigate(AiriRoute.MODELS) }
                )
            }

            // API Keys
            NeuralSectionLabel("مفاتيح API")
            NeuralSectionCard {
                ApiKeyField("OpenAI API Key", openAiKey, showOaiKey, { showOaiKey = it }) { viewModel.setOpenAiApiKey(it) }
                NeuralDivider()
                ApiKeyField("Anthropic API Key", anthropicKey, showAntKey, { showAntKey = it }) { viewModel.setAnthropicApiKey(it) }
                NeuralDivider()
                ApiKeyField("Gemini API Key", geminiKey, showGemKey, { showGemKey = it }) { viewModel.setGeminiApiKey(it) }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ApiKeyField(
    label: String,
    value: String,
    visible: Boolean,
    onVisibilityToggle: (Boolean) -> Unit,
    onSave: (String) -> Unit
) {
    var local by remember(value) { mutableStateOf(value) }
    var saved by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = local,
                onValueChange = { local = it; saved = false },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                placeholder = { Text("sk-...", color = TextTertiary, fontSize = 13.sp) },
                trailingIcon = {
                    IconButton(onClick = { onVisibilityToggle(!visible) }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = null, tint = TextTertiary, modifier = Modifier.size(17.dp))
                    }
                },
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryAccent, unfocusedBorderColor = BorderLight,
                    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                    cursorColor = PrimaryAccent, focusedContainerColor = Surface2, unfocusedContainerColor = Surface2
                ),
                shape = RoundedCornerShape(12.dp)
            )
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!saved && local.isNotBlank()) PrimaryAccent else Surface2)
                    .border(1.dp, if (!saved && local.isNotBlank()) PrimaryAccent else BorderLight, RoundedCornerShape(12.dp))
                    .clickable(enabled = !saved && local.isNotBlank()) { onSave(local.trim()); saved = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (saved) Icons.Outlined.Check else Icons.Outlined.Save,
                    contentDescription = null, tint = if (!saved && local.isNotBlank()) androidx.compose.ui.graphics.Color.White else TextTertiary,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
