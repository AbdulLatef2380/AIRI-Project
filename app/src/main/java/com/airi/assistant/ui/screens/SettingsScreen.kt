package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAppInfo: () -> Unit
) {
    var darkMode by remember { mutableStateOf(true) }
    var arabic by remember { mutableStateOf(true) }
    var compactMode by remember { mutableStateOf(false) }
    var glassEffect by remember { mutableStateOf(true) }
    var accent by remember { mutableStateOf("Cyan") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        ScreenHeader(
            title = "الإعدادات",
            subtitle = "تخصيص اللغة والمظهر وطريقة عرض الواجهة",
            onBack = onBack,
            trailing = {
                TextButton(onClick = onOpenAppInfo) {
                    Text("App info")
                }
            }
        )

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "المظهر") {
            SettingSwitch("الوضع الداكن", darkMode) { darkMode = it }
            SettingSwitch("تأثير الزجاج", glassEffect) { glassEffect = it }
            SettingSwitch("واجهة مضغوطة", compactMode) { compactMode = it }
            OutlinedTextField(
                value = accent,
                onValueChange = { accent = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("لون التمييز") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "اللغة") {
            SettingSwitch("استخدام العربية كلغة رئيسية", arabic) { arabic = it }
            Text("يمكن ربط هذا الخيار لاحقًا بنظام الموارد والترجمة عند إضافة تعدد اللغات الكامل.", color = Color.LightGray)
        }

        Spacer(Modifier.height(12.dp))

        SectionCard(title = "الخصوصية") {
            Text("AIRI مصمم للعمل محليًا. إعدادات الحساب والمزامنة يمكن إضافتها هنا بدون تغيير واجهة المحادثة.", color = Color.LightGray)
        }
    }
}
