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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ai.skills.OfficialSkillLibrary
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
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
    val registry = remember { SkillRegistry(context) }
    val entry = remember(skillId) { OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == skillId } }
    val manifest = entry?.manifest
    val skillInfo = remember(skillId) { registry.getAllSkillInfos().firstOrNull { it.name == skillId } }
    val available = skillInfo?.isConnected != false
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var enabled by remember(skillId) { mutableStateOf(registry.isSkillEnabled(skillId)) }
    if (manifest == null) { LaunchedEffect(Unit) { onBack() }; return }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground) } },
                title = { Text(stringResource(R.string.skill_details_category_label, manifest.category, manifest.version), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.background(Brush.linearGradient(listOf(CosmicAccent.copy(0.20f), AiriTheme.surface))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(shape = AIRIShapes.lg, color = CosmicAccent.copy(0.18f), modifier = Modifier.size(68.dp)) { Box(contentAlignment = Alignment.Center) { Text(manifest.iconEmoji.ifBlank { "✦" }, fontSize = 34.sp) } }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(manifest.name, color = AiriTheme.onSurface, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                            Text(manifest.description, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailBadge(manifest.category, CosmicAccent)
                        DetailBadge("v${manifest.version}", AiriTheme.onSurfaceVariant)
                        if (!available) DetailBadge(stringResource(R.string.skill_connector_required), SemanticWarn)
                    }
                }
            }
            Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.skill_how_works), color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(stringResource(R.string.skill_details_explanation), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp)
                    Text(stringResource(R.string.skill_details_memory, manifest.memoryAccess, manifest.modelAccess), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    if (manifest.dependencies.isNotEmpty()) Text(stringResource(R.string.skill_details_requirements, manifest.dependencies.joinToString()), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.skill_details_security), color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    val riskLabel = when (manifest.riskLevel) {
                        com.airi.assistant.ai.skills.SkillRiskLevel.LOW -> stringResource(R.string.skill_risk_low)
                        com.airi.assistant.ai.skills.SkillRiskLevel.MEDIUM -> stringResource(R.string.skill_risk_medium)
                        com.airi.assistant.ai.skills.SkillRiskLevel.HIGH -> stringResource(R.string.skill_risk_high)
                        com.airi.assistant.ai.skills.SkillRiskLevel.CRITICAL -> stringResource(R.string.skill_risk_critical)
                    }
                    Text(stringResource(R.string.skill_risk_level, riskLabel), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(stringResource(R.string.skill_details_permissions, manifest.permissions.joinToString().ifBlank { "—" }), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    Text(stringResource(R.string.skill_details_tools, manifest.tools.map { it.name }.joinToString().ifBlank { "—" }), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    if (manifest.requiresConfirmation) Text(stringResource(R.string.skill_requires_confirmation), color = SemanticWarn, fontSize = 12.sp)
                    if (manifest.supportsStreaming) Text(stringResource(R.string.skill_supports_streaming), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                    if (manifest.supportsAttachments) Text(stringResource(R.string.skill_supports_attachments), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Text(stringResource(R.string.skill_capabilities), color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            manifest.tools.forEach { tool ->
                Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
                        Surface(shape = AIRIShapes.pill, color = CosmicAccent.copy(0.14f), modifier = Modifier.size(30.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent, modifier = Modifier.size(16.dp)) } }
                        Spacer(Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(tool.name, color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold); Text(tool.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { if (!available) scope.launch { snackbar.showSnackbar(context.getString(R.string.skill_connect_required)) } else { enabled = !enabled; registry.setSkillEnabled(skillId, enabled); scope.launch { snackbar.showSnackbar(context.getString(if (enabled) R.string.skill_enabled else R.string.skill_enable)) } } }, modifier = Modifier.weight(1f), shape = AIRIShapes.lg, colors = ButtonDefaults.buttonColors(containerColor = if (enabled) SemanticSuccess else CosmicAccent)) {
                    Icon(Icons.Outlined.CheckCircle, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(if (enabled) R.string.skill_enabled else R.string.skill_enable))
                }
                OutlinedButton(onClick = { onTry("/skill:${manifest.id} ") }, modifier = Modifier.weight(1f), shape = AIRIShapes.lg) { Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skill_try)) }
            }
            OutlinedButton(onClick = { onExplain("Explain the ${manifest.name} skill, its requirements, permissions, and a safe example of using it in this project.") }, modifier = Modifier.fillMaxWidth(), shape = AIRIShapes.lg) { Icon(Icons.Outlined.HelpOutline, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skill_explain)) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun DetailBadge(text: String, color: Color) {
    Surface(shape = AIRIShapes.pill, color = color.copy(alpha = 0.13f)) { Text(text, color = color, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
}
