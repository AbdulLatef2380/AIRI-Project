package com.airi.assistant.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel

@Composable
fun CustomizationSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val context   = LocalContext.current
    val prefs     = remember { context.getSharedPreferences("airi_custom", Context.MODE_PRIVATE) }
    var aiName    by remember { mutableStateOf(prefs.getString("ai_name", "AIRI") ?: "AIRI") }
    var aiPersona by remember { mutableStateOf(prefs.getString("ai_persona", "") ?: "") }
    var memoryOn  by remember { mutableStateOf(prefs.getBoolean("memory_on", true)) }
    var systemPrompt by remember { mutableStateOf(prefs.getString("system_prompt", "") ?: "") }
    var saved     by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit()
            .putString("ai_name", aiName)
            .putString("ai_persona", aiPersona)
            .putBoolean("memory_on", memoryOn)
            .putString("system_prompt", systemPrompt)
            .apply()
        saved = true
    }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "التخصيص", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NeuralSectionLabel("شخصية الذكاء الاصطناعي")
            NeuralSectionCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = aiName, onValueChange = { aiName = it; saved = false },
                        label = { Text("اسم المساعد", color = TextTertiary, fontSize = 13.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                        colors = neuralColors(), shape = RoundedCornerShape(12.dp))
                    OutlinedTextField(value = aiPersona, onValueChange = { aiPersona = it; saved = false },
                        label = { Text("أسلوب الشخصية", color = TextTertiary, fontSize = 13.sp) },
                        placeholder = { Text("مثال: احترافي ومحترم...", color = TextTertiary, fontSize = 13.sp) },
                        minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth(),
                        colors = neuralColors(), shape = RoundedCornerShape(12.dp))
                }
            }

            NeuralSectionLabel("التعليمات النظامية")
            NeuralSectionCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(value = systemPrompt, onValueChange = { systemPrompt = it; saved = false },
                        label = { Text("System Prompt", color = TextTertiary, fontSize = 13.sp) },
                        placeholder = { Text("أدخل تعليمات خاصة للنموذج...", color = TextTertiary, fontSize = 13.sp) },
                        minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth(),
                        colors = neuralColors(), shape = RoundedCornerShape(12.dp))
                }
            }

            NeuralSectionLabel("الذاكرة والسياق")
            NeuralSectionCard {
                NeuralRowItem(icon = Icons.Outlined.Memory, title = "تفعيل الذاكرة طويلة الأمد",
                    subtitle = "يحتفظ بذاكرة عبر الجلسات",
                    onClick = { /* toggle is handled by trailingContent */ },
                    trailingContent = { NeuralToggle(memoryOn) { memoryOn = it; saved = false } }, showArrow = false)
                NeuralDivider()
                NeuralRowItem(icon = Icons.Outlined.ManageSearch, title = "إدارة الذاكرة",
                    subtitle = "عرض وحذف الذكريات المحفوظة",
                    onClick = { onNavigate(AiriRoute.MEMORY) })
            }

            NeuralAccentButton(
                text = if (saved) "✓ تم الحفظ" else "حفظ الإعدادات",
                onClick = { save() },
                enabled = !saved
            )

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun neuralColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = PrimaryAccent, unfocusedBorderColor = BorderLight,
    focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
    cursorColor = PrimaryAccent, focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
    focusedLabelColor = PrimaryAccent
)
