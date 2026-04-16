package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.theme.CosmicAccent

@Composable
fun AppInfoScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenHeader(
            title = "App info",
            subtitle = "معلومات التطبيق والبنية الحالية",
            onBack = onBack
        )

        Spacer(Modifier.height(16.dp))

        // استخدام المكون الجديد AppInfoSectionCard
        AppInfoSectionCard(title = "AIRI") {
            InfoLine("الاسم", "Android Artificial Intelligence Runtime Interface")
            InfoLine("الإصدار", "1.0")
            InfoLine("الحزمة", "com.airi.assistant")
            InfoLine("الواجهة", "Kotlin + Jetpack Compose")
            InfoLine("التخزين", "Room / ذاكرة محلية")
            InfoLine("الذكاء الاصطناعي", "LLaMA محلي عبر طبقة ai")
        }

        Spacer(Modifier.height(12.dp))

        AppInfoSectionCard(title = "ما تمت إضافته للواجهة") {
            Text(
                text = "قائمة جانبية، اختصارات رئيسية، قوالب، إعدادات نموذج محلي، إعدادات مظهر ولغة، سجل محادثات، وخيار معلومات التطبيق.",
                color = Color.LightGray
            )
        }

        Spacer(Modifier.height(12.dp))

        AppInfoSectionCard(title = "ملاحظة تطوير") {
            Text(
                text = "تمت إضافة مكونات الواجهة المطلوبة مع الحفاظ على منطق ChatViewModel ومسار المعالجة الحالي حتى لا يتضرر التطوير المستقبلي.",
                color = Color.LightGray
            )
        }
    }
}

@Composable
fun AppInfoSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = CosmicAccent // تم استخدام لون السمة الخاص بالمشروع للعنوان
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun InfoLine(
    label: String,
    value: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = CosmicAccent, fontWeight = FontWeight.Bold)
        Text(value, color = Color.White)
    }
}
