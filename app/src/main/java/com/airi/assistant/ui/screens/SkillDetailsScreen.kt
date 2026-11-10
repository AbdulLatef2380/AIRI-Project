package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EventNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ai.skills.OfficialSkillLibrary
import com.airi.assistant.ai.skills.SkillPresentation
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillDetailsScreen(
    skillId: String,
    onBack: () -> Unit,
    onTry: (String) -> Unit = {},
    onExplain: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val registry = remember { SkillRegistry.shared(context) }
    val entry = remember(skillId) { OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == skillId } }
    val manifest = entry?.manifest
    val presentation = manifest?.let { SkillPresentation.localized(it, java.util.Locale.getDefault()) }
    val skillInfo = remember(skillId) { registry.getAllSkillInfos().firstOrNull { it.name == skillId } }
    val available = skillInfo?.isConnected != false
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var enabled by remember(skillId) { mutableStateOf(registry.isSkillEnabled(skillId)) }
    if (manifest == null || presentation == null) { LaunchedEffect(Unit) { onBack() }; return }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground) } },
                title = { Text(stringResource(R.string.skill_details_category_label, presentation.category, manifest.version), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(shape = AIRIShapes.lg, color = AiriTheme.onSurface.copy(alpha = 0.10f), modifier = Modifier.size(58.dp)) { Box(contentAlignment = Alignment.Center) { Icon(skillIconFor(skillId), presentation.name, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(28.dp)) } }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(presentation.name, color = AiriTheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(presentation.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailBadge(presentation.category, AiriTheme.onSurfaceVariant)
                        DetailBadge("v${manifest.version}", AiriTheme.onSurfaceVariant)
                        if (!available) DetailBadge(stringResource(R.string.skill_connector_required), SemanticWarn)
                    }
                }
            }
            Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.skill_capabilities), color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    manifest.tools.take(4).forEach { tool ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.CheckCircle, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
                            Text(tool.name, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
                        }
                    }
                    if (manifest.requiresConfirmation) Text(stringResource(R.string.skill_requires_confirmation), color = SemanticWarn, fontSize = 11.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (!available) scope.launch { snackbar.showSnackbar(context.getString(R.string.skill_connect_required)) } else { enabled = !enabled; registry.setSkillEnabled(skillId, enabled); scope.launch { snackbar.showSnackbar(context.getString(if (enabled) R.string.skill_enabled else R.string.skill_enable)) } } }, modifier = Modifier.weight(1f), shape = AIRIShapes.lg, colors = ButtonDefaults.buttonColors(containerColor = AiriTheme.onSurface.copy(alpha = 0.12f), contentColor = AiriTheme.onSurface)) {
                    Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(if (enabled) R.string.skill_enabled else R.string.skill_enable))
                }
                OutlinedButton(onClick = { onTry("/skill:${manifest.id} ") }, modifier = Modifier.weight(1f), shape = AIRIShapes.lg, colors = ButtonDefaults.outlinedButtonColors(contentColor = AiriTheme.onSurfaceVariant)) { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skill_try)) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun DetailBadge(text: String, color: Color) {
    Surface(shape = AIRIShapes.pill, color = color.copy(alpha = 0.13f)) { Text(text, color = color, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
}

private fun skillIconFor(id: String) = when {
    id.contains("web") || id.contains("research") || id.contains("reader") -> Icons.Outlined.Language
    id.contains("translate") -> Icons.Outlined.Translate
    id.contains("code") || id.contains("github") -> Icons.Outlined.Code
    id.contains("task") || id.contains("reminder") || id.contains("calendar") -> Icons.Outlined.EventNote
    id.contains("memory") -> Icons.Outlined.Psychology
    id.contains("document") || id.contains("pdf") -> Icons.Outlined.Description
    id.contains("file") || id.contains("drive") -> Icons.Outlined.FolderOpen
    id.contains("media") || id.contains("ocr") -> Icons.Outlined.Image
    id.contains("gmail") -> Icons.Outlined.Email
    id.contains("telegram") -> Icons.Outlined.Send
    else -> Icons.Outlined.Extension
}
