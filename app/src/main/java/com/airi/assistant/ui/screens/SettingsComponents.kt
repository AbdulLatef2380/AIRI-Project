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
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.AIRIShapes
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
        shape    = AIRIShapes.md,
        color    = AiriTheme.surfaceVariant,
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
            color      = AiriTheme.onBackground
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
            Text(label, fontSize = 14.sp, color = AiriTheme.onBackground)
            if (sublabel.isNotEmpty()) {
                Text(sublabel, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
        Icon(
            imageVector        = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint               = AiriTheme.onSurfaceVariant.copy(alpha = 0.35f),
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
                color    = if (destructive) Color(0xFFFF6B6B) else AiriTheme.onSurface
            )
            if (sublabel.isNotEmpty()) {
                Text(sublabel, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
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
        Text(label, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, color = AiriTheme.onBackground)
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
                shape    = AIRIShapes.sm,
                color    = if (isSelected) CosmicAccent.copy(alpha = 0.12f)
                           else AiriTheme.outline,
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
                        color    = if (isSelected) CosmicAccent else AiriTheme.onSurface
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
            color    = AiriTheme.onBackground.copy(alpha = 0.6f)
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
            color    = AiriTheme.onBackground.copy(alpha = 0.6f)
        )
    }
}

@Composable
//  FIX: was a dead label — now navigates to ModelLibraryScreen where keys
// are actually entered via SecureApiKeyStore. onNavigate defaults to no-op so
// the AIModelsSettingsScreen zero-arg call still compiles; the call site in that
// screen is also updated to pass onNavigate.
fun ApiKeysSection(onNavigate: (String) -> Unit = {}) {
    SettingsSurface {
        SettingsCategoryHeader(icon = Icons.Outlined.Key, title = "API Keys")
        Spacer(Modifier.height(4.dp))
        Text(
            text      = "Add OpenAI, Anthropic, or Gemini keys to enable cloud models.",
            fontSize  = 12.sp,
            color     = AiriTheme.onSurfaceVariant,
            lineHeight = 17.sp
        )
        Spacer(Modifier.height(8.dp))
        SettingsNavigationRow(
            label    = "Manage API Keys",
            sublabel = "Add, edit, or remove cloud provider keys",
            onClick  = { onNavigate(com.airi.assistant.ui.AiriRoute.MODEL_LIBRARY) }
        )
    }
}
