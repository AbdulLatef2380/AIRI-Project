package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val conversations = listOf(
        "محادثة جديدة",
        "أوامر الجهاز",
        "تحليل الملفات",
        "إعدادات النموذج"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ScreenHeader(
            title = "المحادثات",
            subtitle = "قائمة جانبية لسجل المحادثات والمساحات المهمة",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(conversations) { title ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold)
                    Text("جاهز للربط بقاعدة بيانات المحادثات عند تفعيل الحفظ الكامل.", color = Color.LightGray)
                }
            }
        }
    }
}
