@file:OptIn(ExperimentalMaterial3Api::class)
package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.community.CommunitySkill
import com.airi.assistant.community.CommunitySkillHub
import com.airi.assistant.community.TrustScoringEngine
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * CommunitySkillsScreen — import, verify, sandbox-test, and manage community skills.
 *
 * Tabs:
 *  - My Skills   → list of imported community skills
 *  - Import      → import by URL, paste JSON, or file
 *  - Trust       → view trust breakdown for a selected skill
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunitySkillsScreen(
    hub:    CommunitySkillHub,
    onBack: () -> Unit = {}
) {
    val scope      = rememberCoroutineScope()
    val skills     by hub.skills.collectAsState()
    val isLoading  by hub.isLoading.collectAsState()

    var selectedTab   by remember { mutableIntStateOf(0) }
    var snackMessage  by remember { mutableStateOf<String?>(null) }
    val snackState    = remember { SnackbarHostState() }
    var selectedSkill by remember { mutableStateOf<CommunitySkill?>(null) }

    LaunchedEffect(snackMessage) {
        snackMessage?.let { snackState.showSnackbar(it); snackMessage = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.community_skills_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        snackbarHost   = { SnackbarHost(snackState) },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab, containerColor = AiriTheme.background, contentColor = CosmicAccent) {
                listOf("My Skills (${skills.size})", "Import", "Trust Score").forEachIndexed { i, t ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(t, fontSize = 12.sp, color = if (selectedTab == i) CosmicAccent else AiriTheme.onSurfaceVariant) })
                }
            }

            when (selectedTab) {
                0 -> MySkillsTab(
                    skills     = skills,
                    isLoading  = isLoading,
                    onRemove   = { skill ->
                        hub.remove(skill.id)
                        snackMessage = "${skill.name} removed."
                    },
                    onTrustTap = { skill ->
                        selectedSkill = skill
                        selectedTab = 2
                    },
                    onSandboxTest = { skill ->
                        scope.launch {
                            val result = hub.sandboxTest(skill)
                            snackMessage = if (result.passed) "✓ ${result.message}" else "✗ ${result.message}"
                        }
                    }
                )
                1 -> ImportTab(hub = hub, onImported = { snackMessage = "✓ ${it.name} imported!"; selectedSkill = it })
                2 -> TrustScoreTab(skill = selectedSkill, hub = hub)
            }
        }
    }
}
@Composable
private fun MySkillsTab(
    skills:        List<CommunitySkill>,
    isLoading:     Boolean,
    onRemove:      (CommunitySkill) -> Unit,
    onTrustTap:    (CommunitySkill) -> Unit,
    onSandboxTest: (CommunitySkill) -> Unit
) {
    if (skills.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Groups, null, Modifier.size(56.dp), tint = AiriTheme.onSurfaceVariant)
                Text(stringResource(R.string.community_no_skills), color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.community_no_skills_desc), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(skills, key = { it.id }) { skill ->
            CommunitySkillCard(skill, onRemove, onTrustTap, onSandboxTest)
        }
    }
}

@Composable
private fun CommunitySkillCard(
    skill:         CommunitySkill,
    onRemove:      (CommunitySkill) -> Unit,
    onTrustTap:    (CommunitySkill) -> Unit,
    onSandboxTest: (CommunitySkill) -> Unit
) {
    val breakdown = remember(skill) { TrustScoringEngine.score(skill) }
    var expanded  by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = AIRIShapes.md
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).background(CosmicAccent.copy(0.15f), AIRIShapes.sm),
                    contentAlignment = Alignment.Center
                ) { Text("🔌", fontSize = 20.sp) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    Text("${skill.publisher.displayName} · v${skill.version}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                }
                // Trust badge
                Box(
                    Modifier.background(tierColor(breakdown.tier).copy(0.15f), AIRIShapes.xs)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text("${breakdown.tier.emoji} ${breakdown.totalScore}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tierColor(breakdown.tier))
                }
            }

            AnimatedVisibility(expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(skill.description, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
                    skill.sourceUrl?.let {
                        Text(stringResource(R.string.community_skill_source_prefix, it), fontSize = 11.sp, color = CosmicAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(color = AiriTheme.outline)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onSandboxTest(skill) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Science, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.community_sandbox), fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { onTrustTap(skill) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Shield, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.community_trust), fontSize = 12.sp)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { onRemove(skill) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "Remove", Modifier.size(18.dp), tint = SemanticError)
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    if (expanded) "Collapse" else "Expand",
                    Modifier.size(18.dp),
                    tint = AiriTheme.onSurfaceVariant
                )
            }
        }
    }
}
@Composable
private fun ImportTab(hub: CommunitySkillHub, onImported: (CommunitySkill) -> Unit) {
    val scope = rememberCoroutineScope()
    var importUrl   by remember { mutableStateOf("") }
    var jsonPaste   by remember { mutableStateOf("") }
    var result      by remember { mutableStateOf<String?>(null) }
    var isLoading   by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            Text(stringResource(R.string.community_import_title), fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = AiriTheme.onBackground)
            Text(stringResource(R.string.community_import_desc), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("From URL", "Paste JSON").forEachIndexed { i, t ->
                    FilterChip(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i; result = null },
                        label    = { Text(t) },
                        colors   = FilterChipDefaults.filterChipColors(selectedContainerColor = CosmicAccent.copy(0.15f), selectedLabelColor = CosmicAccent)
                    )
                }
            }
        }

        if (selectedTab == 0) {
            item {
                OutlinedTextField(
                    value         = importUrl,
                    onValueChange = { importUrl = it; result = null },
                    label         = { Text(stringResource(R.string.skill_url_label)) },
                    placeholder   = { Text("https://raw.githubusercontent.com/…/skill.json") },
                    modifier      = Modifier.fillMaxWidth(),
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = AiriTheme.outline)
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            result    = "Importing…"
                            when (val r = hub.importFromUrl(importUrl.trim())) {
                                is CommunitySkillHub.ImportResult.Success -> { result = "✓ ${r.skill.name} imported!"; onImported(r.skill) }
                                is CommunitySkillHub.ImportResult.Error   -> result = "✗ ${r.message}"
                                is CommunitySkillHub.ImportResult.SecurityBlocked -> result = "🚫 ${r.reason}"
                            }
                            isLoading = false
                        }
                    },
                    enabled = importUrl.isNotBlank() && !isLoading,
                    colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AiriTheme.onSurface)
                    else Icon(Icons.Default.Download, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.community_import_from_url))
                }
            }
        } else {
            item {
                OutlinedTextField(
                    value         = jsonPaste,
                    onValueChange = { jsonPaste = it; result = null },
                    label         = { Text(stringResource(R.string.paste_skill_json_label)) },
                    modifier      = Modifier.fillMaxWidth().height(200.dp),
                    textStyle     = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = AiriTheme.outline)
                )
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            result    = "Parsing…"
                            when (val r = hub.importFromJson(jsonPaste.trim())) {
                                is CommunitySkillHub.ImportResult.Success -> { result = "✓ ${r.skill.name} imported!"; onImported(r.skill) }
                                is CommunitySkillHub.ImportResult.Error   -> result = "✗ ${r.message}"
                                is CommunitySkillHub.ImportResult.SecurityBlocked -> result = "🚫 ${r.reason}"
                            }
                            isLoading = false
                        }
                    },
                    enabled = jsonPaste.isNotBlank() && !isLoading,
                    colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                ) {
                    Icon(Icons.Default.Code, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.community_import_json))
                }
            }
        }

        result?.let {
            item {
                val isError = it.startsWith("✗") || it.startsWith("🚫")
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (isError) SemanticError.copy(0.1f) else SemanticSuccess.copy(0.1f)),
                    shape  = AIRIShapes.md
                ) {
                    Text(it, Modifier.padding(12.dp), fontSize = 13.sp, color = if (isError) SemanticError else SemanticSuccess)
                }
            }
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, null, Modifier.size(20.dp), tint = CosmicAccent)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.community_security_scan_active), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                        Text(stringResource(R.string.community_security_scan_desc), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
@Composable
private fun TrustScoreTab(skill: CommunitySkill?, hub: CommunitySkillHub) {
    if (skill == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Shield, null, Modifier.size(48.dp), tint = AiriTheme.onSurfaceVariant)
                Text(stringResource(R.string.community_select_skill_trust), color = AiriTheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(stringResource(R.string.community_select_skill_trust_desc), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
        return
    }

    val breakdown = remember(skill) { hub.getTrustBreakdown(skill) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = tierColor(breakdown.tier).copy(0.1f)),
                shape  = AIRIShapes.xl,
                border = androidx.compose.foundation.BorderStroke(1.dp, tierColor(breakdown.tier).copy(0.3f))
            ) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(skill.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AiriTheme.onBackground)
                        Text(breakdown.tier.description, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 18.sp)
                        Spacer(Modifier.height(4.dp))
                        Text("${breakdown.tier.emoji} ${breakdown.tier.label} Tier", fontWeight = FontWeight.SemiBold, color = tierColor(breakdown.tier))
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${breakdown.totalScore}", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = tierColor(breakdown.tier))
                        Text(stringResource(R.string.community_score_suffix), fontSize = 14.sp, color = AiriTheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.community_trust_signals), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
        }

        items(breakdown.signals) { signal ->
            TrustSignalRow(signal)
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = AIRIShapes.md
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Science, null, Modifier.size(20.dp), tint = CosmicAccent)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(stringResource(R.string.community_sandbox_level_prefix, breakdown.tier.sandboxLevel.name), fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                        Text(sandboxLevelDescription(breakdown.tier.sandboxLevel), fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustSignalRow(signal: TrustScoringEngine.TrustBreakdown.SignalDetail) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = AIRIShapes.md
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (signal.passed) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                null, Modifier.size(18.dp),
                tint = if (signal.passed) SemanticSuccess else AiriTheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Text(signal.label, Modifier.weight(1f), fontSize = 13.sp, color = AiriTheme.onBackground)
            Spacer(Modifier.width(8.dp))
            Text("${signal.score}/${signal.maxScore}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (signal.passed) SemanticSuccess else AiriTheme.onSurfaceVariant)
        }
    }
}

private fun tierColor(tier: TrustScoringEngine.TrustTier): Color = when (tier) {
    TrustScoringEngine.TrustTier.UNVERIFIED -> SemanticWarn
    TrustScoringEngine.TrustTier.BASIC      -> Color(0xFF64B5F6)
    TrustScoringEngine.TrustTier.TRUSTED    -> SemanticSuccess
    TrustScoringEngine.TrustTier.VERIFIED   -> CosmicAccent
}

private fun sandboxLevelDescription(level: TrustScoringEngine.SandboxLevel): String = when (level) {
    TrustScoringEngine.SandboxLevel.RESTRICTED -> "No file or network access. Read-only operations only."
    TrustScoringEngine.SandboxLevel.STANDARD   -> "Standard sandbox. Network access, no file write."
    TrustScoringEngine.SandboxLevel.RELAXED    -> "Relaxed sandbox. Most capabilities available."
    TrustScoringEngine.SandboxLevel.FULL       -> "Full capabilities. Publisher has been verified by AIRI."
}
