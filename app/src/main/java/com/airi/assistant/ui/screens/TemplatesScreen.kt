package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent

data class TemplateEntry(
    val title: String,
    val description: String,
    val prompt: String
)

@Composable
fun TemplatesScreen(
    onBack: () -> Unit,
    onOpenModelSettings: () -> Unit
) {
    val templates = remember {
        listOf(
            TemplateEntry(
                "تشغيل تطبيق",
                "قالب لأوامر فتح التطبيقات والتحكم السريع.",
                "افتح التطبيق المطلوب ثم تحقق من حالة الشاشة."
            ),
            TemplateEntry(
                "تلخيص ملف",
                "قالب لمعالجة ملف نصي أو مستند بعد إرفاقه.",
                "اقرأ الملف المرفق واستخرج أهم النقاط والمهام."
            ),
            TemplateEntry(
                "تحليل صورة",
                "قالب لوصف الصور واستخراج التفاصيل المهمة.",
                "حلل الصورة المرفقة واشرح العناصر المهمة فيها."
            ),
            TemplateEntry(
                "خطة تنفيذ",
                "قالب لتحويل هدف المستخدم إلى خطوات منظمة.",
                "حوّل الهدف التالي إلى خطة آمنة وقابلة للتنفيذ."
            )
        )
    }
    var selected by remember { mutableStateOf<TemplateEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .padding(16.dp)
    ) {
        ScreenHeader(
            title = "القوالب",
            subtitle = "عرض وتحميل قوالب الأوامر الجاهزة",
            onBack = onBack
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = onOpenModelSettings,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
        ) {
            Text("ضبط النموذج المحلي قبل استخدام القوالب", color = Color.Black)
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(templates) { item ->
                TemplateCard(
                    item = item,
                    onView = { selected = item },
                    onDownload = { selected = item }
                )
            }
        }
    }

    selected?.let { item ->
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = {
                TextButton(onClick = { selected = null }) {
                    Text("تم")
                }
            },
            title = { Text(item.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.description)
                    Text(item.prompt, color = CosmicAccent)
                }
            }
        )
    }
}

@Composable
fun TemplateCard(
    item: TemplateEntry,
    onView: () -> Unit,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Text(item.title, color = CosmicAccent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(item.description, color = Color.LightGray)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onView) {
                Text("عرض")
            }
            Button(onClick = onDownload) {
                Text("تحميل")
            }
        }
    }
}
