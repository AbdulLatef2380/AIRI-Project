package com.airi.assistant.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Assistant
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.system.LanguageManager
import com.airi.assistant.system.LanguageOption
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.SurfaceRaised
import com.airi.assistant.ui.viewmodel.ChatViewModel

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = SurfaceRaised,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content  = content
        )
    }
}

@Composable
fun SettingsCategoryHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector     = icon,
            contentDescription = null,
            tint            = CosmicAccent,
            modifier        = Modifier.size(18.dp)
        )
        Text(
            text       = title,
            fontSize   = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color      = Color.White
        )
    }
}

@Composable
fun SettingsNavigationRow(label: String, sublabel: String = "", onClick: () -> Unit) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp, color = Color.White)
            if (sublabel.isNotEmpty()) {
                Text(sublabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
        Icon(
            imageVector        = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint               = Color.White.copy(alpha = 0.35f),
            modifier           = Modifier.size(18.dp)
        )
    }
}

@Composable
fun SettingsActionRow(
    label:      String,
    sublabel:   String  = "",
    destructive: Boolean = false,
    onClick:    () -> Unit
) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text     = label,
                fontSize = 14.sp,
                color    = if (destructive) Color(0xFFFF6B6B) else Color.White
            )
            if (sublabel.isNotEmpty()) {
                Text(sublabel, fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
        Text(value, fontSize = 13.sp, color = Color.White)
    }
}

@Composable
fun LanguageSelector(
    selectedLanguage:   String,
    onLanguageSelected: (LanguageOption) -> Unit
) {
    val languages = LanguageManager.supportedLanguages
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        languages.forEach { lang ->
            val isSelected = lang.code == selectedLanguage
            Surface(
                onClick  = { onLanguageSelected(lang) },
                shape    = RoundedCornerShape(10.dp),
                color    = if (isSelected) CosmicAccent.copy(alpha = 0.12f)
                           else Color.White.copy(alpha = 0.04f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(lang.flag, fontSize = 18.sp)
                    Text(
                        text     = lang.displayName,
                        fontSize = 13.sp,
                        color    = if (isSelected) CosmicAccent else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DefaultAssistantSection(activity: Activity?) {
    if (activity == null) return
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Assistant, title = "Default Assistant")
        Spacer(Modifier.height(8.dp))
        Text(
            text     = "Set AIRI as the default digital assistant in Android system settings.",
            fontSize = 12.sp,
            color    = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun ObservabilitySection(onNavigate: (String) -> Unit) {
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Analytics, title = "Observability")
        Spacer(Modifier.height(8.dp))
        SettingsNavigationRow(
            label    = "Observability Dashboard",
            sublabel = "View agent execution logs and diagnostics"
        ) { onNavigate(AiriRoute.OBSERVABILITY) }
    }
}

@Composable
fun SkillsSection(viewModel: ChatViewModel) {
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Lightbulb, title = "Skills")
        Spacer(Modifier.height(8.dp))
        Text(
            text     = "Manage enabled skills and custom skill builders.",
            fontSize = 12.sp,
            color    = Color.White.copy(alpha = 0.6f)
        )
    }
}

@Composable
fun ApiKeysSection() {
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Key, title = "API Keys")
        Spacer(Modifier.height(8.dp))
        Text(
            text     = "Configure API keys for cloud providers.",
            fontSize = 12.sp,
            color    = Color.White.copy(alpha = 0.6f)
        )
    }
}
