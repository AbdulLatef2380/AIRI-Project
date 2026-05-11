package com.airi.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.LanguageManager
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*

@Composable
fun GeneralSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("airi_general", Context.MODE_PRIVATE) }
    var defaultAssist by remember { mutableStateOf(prefs.getBoolean("default_assist", false)) }
    var hapticEnabled by remember { mutableStateOf(prefs.getBoolean("haptic", true)) }
    var autoScroll    by remember { mutableStateOf(prefs.getBoolean("auto_scroll", true)) }
    var streamingUI   by remember { mutableStateOf(prefs.getBoolean("streaming_ui", true)) }

    // ── Language state ────────────────────────────────────────────────────────
    var currentLang by remember {
        mutableStateOf(LanguageManager.currentLanguage)
    }
    var showLangRestartNote by remember { mutableStateOf(false) }

    fun save() {
        prefs.edit()
            .putBoolean("default_assist", defaultAssist)
            .putBoolean("haptic", hapticEnabled)
            .putBoolean("auto_scroll", autoScroll)
            .putBoolean("streaming_ui", streamingUI)
            .apply()
    }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "الإعدادات العامة", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── UI section ────────────────────────────────────────────────────
            NeuralSectionLabel("الواجهة")
            NeuralSectionCard {
                NeuralRowItem(
                    icon     = Icons.Outlined.Animation,
                    title    = "واجهة البث المباشر",
                    subtitle = "عرض الردود وهي تُكتب حرفاً بحرف",
                    trailingContent = {
                        NeuralToggle(checked = streamingUI,
                            onCheckedChange = { streamingUI = it; save() })
                    },
                    showChevron = false
                )
                NeuralDivider()
                NeuralRowItem(
                    icon     = Icons.Outlined.VerticalAlignBottom,
                    title    = "التمرير التلقائي",
                    subtitle = "يتمرر إلى أسفل مع الرسائل الجديدة",
                    trailingContent = {
                        NeuralToggle(checked = autoScroll,
                            onCheckedChange = { autoScroll = it; save() })
                    },
                    showChevron = false
                )
                NeuralDivider()
                NeuralRowItem(
                    icon     = Icons.Outlined.Vibration,
                    title    = "التغذية الراجعة اللمسية",
                    subtitle = "اهتزاز خفيف عند التفاعل",
                    trailingContent = {
                        NeuralToggle(checked = hapticEnabled,
                            onCheckedChange = { hapticEnabled = it; save() })
                    },
                    showChevron = false
                )
            }

            // ── Language section ──────────────────────────────────────────────
            NeuralSectionLabel("اللغة")
            NeuralSectionCard {
                // Arabic toggle row
                LanguageToggleRow(
                    lang     = LanguageManager.Language.ARABIC,
                    selected = currentLang == LanguageManager.Language.ARABIC,
                    onSelect = {
                        if (currentLang != LanguageManager.Language.ARABIC) {
                            LanguageManager.setLanguage(LanguageManager.Language.ARABIC)
                            currentLang = LanguageManager.Language.ARABIC
                            showLangRestartNote = true
                        }
                    }
                )
                NeuralDivider()
                // English toggle row
                LanguageToggleRow(
                    lang     = LanguageManager.Language.ENGLISH,
                    selected = currentLang == LanguageManager.Language.ENGLISH,
                    onSelect = {
                        if (currentLang != LanguageManager.Language.ENGLISH) {
                            LanguageManager.setLanguage(LanguageManager.Language.ENGLISH)
                            currentLang = LanguageManager.Language.ENGLISH
                            showLangRestartNote = true
                        }
                    }
                )
                // Restart note
                if (showLangRestartNote) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Outlined.Info, contentDescription = null,
                            tint = CosmicAccent.copy(alpha = 0.7f),
                            modifier = Modifier.size(14.dp))
                        Text(
                            text     = "أعد تشغيل التطبيق لتطبيق اللغة الجديدة",
                            color    = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // ── System section ────────────────────────────────────────────────
            NeuralSectionLabel("النظام")
            NeuralSectionCard {
                NeuralRowItem(
                    icon     = Icons.Outlined.Assistant,
                    title    = "تعيين كمساعد افتراضي",
                    subtitle = "يجعل AIRI مساعدك الافتراضي في Android",
                    trailingContent = {
                        NeuralToggle(checked = defaultAssist, onCheckedChange = {
                            defaultAssist = it; save()
                            if (it) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                                } catch (_: Exception) {}
                            }
                        })
                    },
                    showChevron = false
                )
                NeuralDivider()
                NeuralRowItem(
                    icon     = Icons.Outlined.Accessibility,
                    title    = "خدمة إمكانية الوصول",
                    subtitle = "تمكين تحكم AIRI في واجهة Android",
                    onClick  = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        } catch (_: Exception) {}
                    }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Language row composable ────────────────────────────────────────────────────

@Composable
private fun LanguageToggleRow(
    lang:     LanguageManager.Language,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            // Language flag/icon placeholder
        Box(
            modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text       = if (lang == LanguageManager.Language.ARABIC) "ع" else "En",
                color      = if (selected) CosmicAccent else TextSecondary,
                fontSize   = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = lang.nativeName,
                color      = if (selected) TextPrimary else TextSecondary,
                fontSize   = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text     = if (lang.isRtl) "Right-to-left • RTL" else "Left-to-right • LTR",
                color    = TextTertiary,
                fontSize = 11.sp
            )
        }

        RadioButton(
            selected = selected,
            onClick  = onSelect,
            colors   = RadioButtonDefaults.colors(
                selectedColor   = CosmicAccent,
                unselectedColor = TextTertiary
            )
        )
    }
}
