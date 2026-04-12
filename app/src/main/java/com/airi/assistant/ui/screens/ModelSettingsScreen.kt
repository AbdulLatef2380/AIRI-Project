package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    var modelPath by remember { mutableStateOf("") }
    var contextWindow by remember { mutableStateOf(4096f) }
    var temperature by remember { mutableStateOf(0.7f) }
    var useGpu by remember { mutableStateOf(false) }
    var offlineOnly by remember { mutableStateOf(true) }
    var modelPickerInfo by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenHeader(
            title = "إعداد النموذج المحلي",
            subtitle = "اختيار نموذج LLaMA وتخصيص طريقة التشغيل",
            onBack = onBack,
            trailing = {
                TextButton(onClick = onOpenAppInfo) {
                    Text("App info")
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "ملف النموذج") {
            OutlinedTextField(
                value = modelPath,
                onValueChange = { modelPath = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("مسار النموذج المحلي .gguf") },
                placeholder = { Text("/storage/emulated/0/Download/model.gguf") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = CosmicAccent
                )
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { modelPickerInfo = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
            ) {
                Text("رفع أو اختيار نموذج LLaMA", color = Color.Black)
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "أداء النموذج") {
            Text("حجم السياق: ${contextWindow.toInt()}", color = Color.White)
            Slider(
                value = contextWindow,
                onValueChange = { contextWindow = it },
                valueRange = 1024f..8192f,
                steps = 6
            )
            Text("درجة الإبداع: ${"%.2f".format(temperature)}", color = Color.White)
            Slider(
                value = temperature,
                onValueChange = { temperature = it },
                valueRange = 0.1f..1.4f
            )
            SettingSwitch("استخدام تسريع الجهاز عند توفره", useGpu) { useGpu = it }
            SettingSwitch("الوضع المحلي فقط", offlineOnly) { offlineOnly = it }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "حالة الربط") {
            Text(
                "هذه الصفحة تضيف واجهة التحكم المطلوبة دون تغيير محرك AIRI الحالي. يمكن ربط الأزرار لاحقًا بمدير النماذج الموجود في طبقة الذكاء الاصطناعي.",
                color = Color.LightGray
            )
        }
    }

    if (modelPickerInfo) {
        AlertDialog(
            onDismissRequest = { modelPickerInfo = false },
            confirmButton = {
                TextButton(onClick = { modelPickerInfo = false }) {
                    Text("تم")
                }
            },
            title = { Text("اختيار النموذج") },
            text = {
                Text("تم تجهيز واجهة اختيار نموذج LLaMA. يمكن ربط هذا الزر بمنتقي ملفات Android لاختيار ملفات .gguf عند تفعيل طبقة التنفيذ.")
            }
        )
    }
}

@Composable
fun SettingSwitch(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text, color = Color.White, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}
