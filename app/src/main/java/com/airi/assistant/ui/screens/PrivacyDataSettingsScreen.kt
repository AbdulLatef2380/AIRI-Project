package com.airi.assistant.ui.screens

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel

@Composable
fun PrivacyDataSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout:   () -> Unit
) {
    var showClearDialog  by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "الخصوصية والبيانات", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NeuralSectionLabel("البيانات")
            NeuralSectionCard {
                NeuralRowItem(icon = Icons.Outlined.UploadFile, title = "تصدير البيانات",
                    subtitle = "تصدير محادثاتك وذاكرتك",
                    iconTint = SecondaryAccent, iconBgColor = SecondaryAccent.copy(0.14f),
                    onClick = { viewModel.exportData() })
                NeuralDivider()
                NeuralRowItem(icon = Icons.Outlined.DownloadForOffline, title = "استيراد البيانات",
                    subtitle = "استعادة من نسخة احتياطية",
                    iconTint = AccentHybrid, iconBgColor = AccentHybrid.copy(0.14f),
                    onClick = { /* launch file picker handled in viewModel */ })
            }

            NeuralSectionLabel("الحذف والإزالة")
            NeuralSectionCard {
                NeuralRowItem(icon = Icons.Outlined.DeleteSweep, title = "مسح جميع المحادثات",
                    subtitle = "حذف كل تاريخ الدردشة نهائياً",
                    iconTint = SemanticWarning, iconBgColor = SemanticWarning.copy(0.14f),
                    onClick = { showClearDialog = true })
                NeuralDivider()
                NeuralRowItem(icon = Icons.Outlined.Logout, title = "تسجيل الخروج",
                    subtitle = "الخروج من حسابك الحالي",
                    iconTint = SemanticError, iconBgColor = SemanticError.copy(0.14f),
                    onClick = { showLogoutDialog = true })
            }

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = Surface2, titleContentColor = TextPrimary, textContentColor = TextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("مسح جميع المحادثات") },
            text = { Text("هذا الإجراء لا يمكن التراجع عنه. هل أنت متأكد؟") },
            confirmButton = {
                Button(onClick = { viewModel.clearAllHistory(); showClearDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError)
                ) { Text("مسح الكل", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("إلغاء", color = TextSecondary) }
            }
        )
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            containerColor = Surface2, titleContentColor = TextPrimary, textContentColor = TextSecondary,
            shape = RoundedCornerShape(20.dp),
            title = { Text("تسجيل الخروج") },
            text = { Text("هل تريد تسجيل الخروج من AIRI؟") },
            confirmButton = {
                Button(onClick = { showLogoutDialog = false; onLogout() },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError)
                ) { Text("خروج", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("إلغاء", color = TextSecondary) }
            }
        )
    }
}
