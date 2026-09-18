package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.skills.OfficialSkillLibrary
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import com.airi.assistant.marketplace.GitHubSkillImporter
import com.airi.assistant.R
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.SemanticError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

private enum class ImportSource { STORAGE, GITHUB, AI }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(
    onBack:   () -> Unit,
    onCreate: () -> Unit,
    onEdit:   (String) -> Unit,
    onOpenOfficial: (String) -> Unit = {}
) {
    val context       = LocalContext.current
    val repository    = remember { CustomSkillRepository(context) }
    val skillRegistry = remember { SkillRegistry(context) }
    val scope         = rememberCoroutineScope()

    // getAllSkillInfos() also appends custom skills with author="builtin" — filter them out
    // so they only appear in the "My Custom Skills" section, not the "Official Skills" section.
    var officialSkills by remember { mutableStateOf(skillRegistry.getAllSkillInfos().filter { it.author == "AIRI Official" }) }
    var customSkills   by remember { mutableStateOf(repository.getAllSkills()) }
    var showAddMenu    by remember { mutableStateOf(false) }
    var importSource   by remember { mutableStateOf<ImportSource?>(null) }
    var errorMessage   by remember { mutableStateOf<String?>(null) }
    var isImporting    by remember { mutableStateOf(false) }
    var searchQuery    by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(0) }
    var selectedCategory by rememberSaveable { mutableStateOf("ALL") }
    val connectorAvailability by skillRegistry.connectorAvailability.collectAsState()

    fun reload() {
        officialSkills = skillRegistry.getAllSkillInfos().filter { it.author == "AIRI Official" }
        customSkills   = repository.getAllSkills()
    }
    LaunchedEffect(connectorAvailability) {
        reload()
    }
    val filteredOfficialSkills = officialSkills.filter { info ->
        val matchesSearch = searchQuery.isBlank() ||
            info.name.contains(searchQuery, ignoreCase = true) ||
            info.description.contains(searchQuery, ignoreCase = true)
        val matchesFilter = when (selectedFilter) {
            1 -> info.isConnected
            2 -> !info.isConnected
            else -> true
        }
        val category = OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == info.name }
            ?.manifest?.category ?: "OTHER"
        val matchesCategory = selectedCategory == "ALL" || category == selectedCategory
        matchesSearch && matchesFilter && matchesCategory
    }
    val categories = remember(officialSkills) {
        listOf("ALL") + officialSkills.mapNotNull { info ->
            OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == info.name }?.manifest?.category
        }.distinct().sorted()
    }
    LaunchedEffect(categories) {
        if (selectedCategory !in categories) selectedCategory = "ALL"
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: error("Empty file")
                val skill = parseSkillJson(json)
                check(
                    skillRegistry.registerDynamicFromManifest(
                        manifest = skill.toManifest(),
                        endpoint = skill.config.endpoint,
                        method = skill.config.method,
                        bodyTemplate = skill.config.bodyTemplate
                    )
                ) { "The skill manifest or endpoint is not valid." }
                withContext(Dispatchers.Main) { reload(); importSource = null }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.skill_import_failed, e.message ?: "")
                }
            }
        }
    }

    val activeCount = officialSkills.count { it.isEnabled && it.isConnected } +
        customSkills.count { skillRegistry.isCustomSkillAvailable(it) }
    val totalCount = officialSkills.size + customSkills.size
    val connectedCount = officialSkills.count { it.isConnected }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(stringResource(R.string.skill_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(stringResource(R.string.skill_official_section), color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddMenu = true }) {
                        Surface(shape = AIRIShapes.pill, color = CosmicAccent.copy(alpha = 0.14f)) {
                            Icon(Icons.Default.Add, stringResource(R.string.cd_add_skill), tint = CosmicAccent, modifier = Modifier.padding(8.dp))
                        }
                    }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        AddOption(Icons.Outlined.Edit, stringResource(R.string.skill_menu_create)) { showAddMenu = false; onCreate() }
                        AddOption(Icons.Outlined.FolderOpen, stringResource(R.string.skill_menu_import_storage)) { showAddMenu = false; importSource = ImportSource.STORAGE; filePicker.launch("application/json") }
                        AddOption(Icons.Outlined.Code, stringResource(R.string.skill_menu_import_github)) { showAddMenu = false; importSource = ImportSource.GITHUB }
                        AddOption(Icons.Outlined.AutoAwesome, stringResource(R.string.skill_menu_create_with_airi)) { showAddMenu = false; importSource = ImportSource.AI }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            errorMessage?.let { msg ->
                Surface(color = SemanticError.copy(alpha = 0.13f), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = AIRIShapes.md) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Warning, null, tint = SemanticError, modifier = Modifier.size(18.dp))
                        Text(msg, color = SemanticError, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, null, tint = SemanticError) }
                    }
                }
            }
            if (importSource == ImportSource.GITHUB) {
                GitHubImportDialog(isImporting, { importSource = null }) { rawUrl ->
                    scope.launch {
                        isImporting = true
                        val result = withContext(Dispatchers.IO) { GitHubSkillImporter.importFromUrl(rawUrl) }
                        isImporting = false
                        if (result.success && result.skill != null && result.manifest != null) {
                            val registered = skillRegistry.registerDynamicFromManifest(result.manifest, result.skill.config.endpoint, result.skill.config.method, result.skill.config.bodyTemplate)
                            if (!registered) errorMessage = context.getString(R.string.skill_import_github_failed, "The skill endpoint could not be registered.")
                            else { reload(); importSource = null; if (result.warnings.isNotEmpty()) errorMessage = "Imported with ${result.warnings.size} warning(s): " + result.warnings.take(2).joinToString("; ") }
                        } else { errorMessage = context.getString(R.string.skill_import_github_failed, result.errors.take(3).joinToString("; ")); importSource = null }
                    }
                }
            }
            if (importSource == ImportSource.AI) {
                AiSkillCreateDialog({ importSource = null }) { name, description, endpoint ->
                    val skill = CustomSkill(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        description = description,
                        type = SkillType.API,
                        config = SkillConfig(endpoint = endpoint, method = "POST", bodyTemplate = "{\"input\": \"{{input}}\"}"),
                        createdAt = System.currentTimeMillis()
                    )
                    if (skillRegistry.registerDynamicFromManifest(skill.toManifest(), endpoint, skill.config.method, skill.config.bodyTemplate)) { reload(); importSource = null } else errorMessage = context.getString(R.string.skill_invalid_manifest)
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                SkillHeroCard(totalCount, activeCount, connectedCount, onCreate)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = { if (searchQuery.isNotEmpty()) IconButton({ searchQuery = "" }) { Icon(Icons.Outlined.Close, stringResource(R.string.terminal_close_search_cd)) } },
                    placeholder = { Text(stringResource(R.string.skill_search_hint)) }, shape = AIRIShapes.lg,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = AiriTheme.outline, focusedContainerColor = AiriTheme.surface, unfocusedContainerColor = AiriTheme.surface)
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.skill_filter_all, R.string.skill_filter_connected, R.string.skill_filter_external).forEachIndexed { index, res ->
                        SkillFilterChip(stringResource(res), selectedFilter == index) { selectedFilter = index }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { category -> SkillFilterChip(category.lowercase().replaceFirstChar { it.uppercase() }, selectedCategory == category) { selectedCategory = category } }
                }
                Spacer(Modifier.height(18.dp))
                SkillSectionHeader(stringResource(R.string.skill_official_section), filteredOfficialSkills.size)
                Spacer(Modifier.height(8.dp))
                filteredOfficialSkills.forEach { info ->
                    OfficialSkillCard(info, { onOpenOfficial(info.name) }) { enabled -> skillRegistry.setSkillEnabled(info.name, enabled); reload() }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(8.dp))
                SkillSectionHeader(stringResource(R.string.skill_custom_section), customSkills.size)
                Spacer(Modifier.height(8.dp))
                if (customSkills.isEmpty()) EmptySkillsCard(onCreate) else customSkills.forEach { skill ->
                    SkillCard(skill, { onEdit(skill.id) }) { repository.deleteSkill(skill.id); reload() }
                    Spacer(Modifier.height(10.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
@Composable
private fun AddOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp)) },
        text = { Text(label, fontSize = 14.sp) },
        onClick = onClick
    )
}
/**
 * Card for a first-party AIRI skill.
 *
 * - Shows emoji, display name, and description sourced from [OfficialSkillLibrary].
 * - Shows OFFICIAL badge for direct skills; CONNECTOR badge for gated ones.
 * - Provides a Switch to enable/disable via [SkillRegistry.setSkillEnabled].
 * - Connector-gated skills (isConnected=false) show a muted "Requires connector"
 *   note and have their Switch disabled — they can't be activated without setup.
 */
@Composable
private fun SkillHeroCard(total: Int, active: Int, connected: Int, onCreate: () -> Unit) {
    Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.background(Brush.linearGradient(listOf(CosmicAccent.copy(0.20f), AiriTheme.surface, AiriTheme.surface))).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("✦", color = CosmicAccent, fontSize = 28.sp)
                    Text(stringResource(R.string.skill_title), color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 24.sp)
                    Text(stringResource(R.string.skill_no_skills_desc), color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent.copy(0.75f), modifier = Modifier.size(42.dp).padding(5.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkillStat(total.toString(), stringResource(R.string.skill_official_section))
                SkillStat(active.toString(), stringResource(R.string.skill_filter_connected))
                SkillStat(connected.toString(), stringResource(R.string.skill_connector_required))
            }
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth(), shape = AIRIShapes.lg, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) {
                Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.skill_create_button), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable private fun SkillStat(value: String, label: String) {
    Column(Modifier.weight(1f).clip(AIRIShapes.md).background(AiriTheme.onSurface.copy(alpha = 0.06f)).padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun SkillFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 12.sp) }, leadingIcon = if (selected) ({ Icon(Icons.Outlined.Check, null, Modifier.size(14.dp)) }) else null, shape = AIRIShapes.pill, colors = FilterChipDefaults.filterChipColors(containerColor = AiriTheme.surface, labelColor = AiriTheme.onSurfaceVariant, selectedContainerColor = CosmicAccent.copy(0.16f), selectedLabelColor = CosmicAccent, selectedLeadingIconColor = CosmicAccent), border = FilterChipDefaults.filterChipBorder(true, selected, AiriTheme.outline, CosmicAccent.copy(0.5f)))
}

@Composable private fun SkillSectionHeader(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, color = AiriTheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Surface(shape = AIRIShapes.pill, color = CosmicAccent.copy(0.12f)) { Text(count.toString(), color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)) }
    }
}

@Composable private fun EmptySkillsCard(onCreate: () -> Unit) {
    Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Extension, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
            Text(stringResource(R.string.skill_no_skills_desc), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
            TextButton(onClick = onCreate) { Text(stringResource(R.string.skill_create_button), color = CosmicAccent) }
        }
    }
}

@Composable private fun OfficialSkillCard(info: SkillRegistry.SkillInfo, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val entry = remember(info.name) { OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == info.name } }
    val displayName = entry?.manifest?.name ?: info.name.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    val emoji = entry?.manifest?.iconEmoji?.ifBlank { "✦" } ?: "✦"
    val needsConnector = !info.isConnected
    Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = if (info.isEnabled) 3.dp else 1.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Surface(shape = AIRIShapes.lg, color = if (info.isEnabled) CosmicAccent.copy(0.16f) else AiriTheme.onSurface.copy(0.07f), modifier = Modifier.size(50.dp)) { Box(contentAlignment = Alignment.Center) { Text(emoji, fontSize = 25.sp) } }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayName, color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (needsConnector) Surface(shape = AIRIShapes.pill, color = SemanticWarn.copy(0.14f)) { Text(stringResource(R.string.skill_connector_required), color = SemanticWarn, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)) }
                    }
                    Text(info.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (!needsConnector) Switch(checked = info.isEnabled, onCheckedChange = onToggle, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = CosmicAccent, uncheckedThumbColor = AiriTheme.onSurfaceVariant, uncheckedTrackColor = AiriTheme.outline))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Surface(shape = AIRIShapes.pill, color = if (info.executionKind == SkillRegistry.ExecutionKind.LOCAL) SemanticSuccess.copy(0.13f) else CosmicAccent.copy(0.13f)) { Text(stringResource(if (info.executionKind == SkillRegistry.ExecutionKind.LOCAL) R.string.skill_type_local else R.string.skill_type_cloud), color = if (info.executionKind == SkillRegistry.ExecutionKind.LOCAL) SemanticSuccess else CosmicAccent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                Text("v${info.version} · ${info.author}", color = AiriTheme.onSurfaceVariant.copy(0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.ChevronLeft, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable private fun SkillCard(skill: CustomSkill, onClick: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = AIRIShapes.xl, color = AiriTheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = AIRIShapes.lg, color = CosmicAccent.copy(0.14f), modifier = Modifier.size(48.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Extension, null, tint = CosmicAccent, modifier = Modifier.size(24.dp)) } }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(skill.name, color = AiriTheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(skill.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(skill.type.name, color = CosmicAccent, fontSize = 10.sp)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.cd_delete), tint = SemanticError) }
        }
    }
}

@Composable
private fun GitHubImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var rawUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = AiriTheme.surface,
        title = {
            Text(
                stringResource(R.string.skill_menu_import_github),
                color      = AiriTheme.onBackground,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.skill_github_import_desc),
                    color    = AiriTheme.onBackground.copy(0.6f),
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value         = rawUrl,
                    onValueChange = { rawUrl = it },
                    placeholder   = {
                        Text(
                            "https://raw.githubusercontent.com/…/skill.json",
                            color    = AiriTheme.onBackground.copy(0.3f),
                            fontSize = 11.sp
                        )
                    },
                    singleLine = true,
                    modifier   = Modifier.fillMaxWidth(),
                    colors     = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.onSurface.copy(0.15f),
                        focusedTextColor     = AiriTheme.onSurface,
                        unfocusedTextColor   = AiriTheme.onSurface
                    )
                )
                if (isImporting) LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color    = CosmicAccent
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (rawUrl.isNotBlank()) onImport(rawUrl.trim()) },
                enabled  = rawUrl.isNotBlank() && !isImporting
            ) {
                Text(stringResource(R.string.import_action), color = CosmicAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.55f))
            }
        }
    )
}
@Composable
private fun AiSkillCreateDialog(
    onDismiss: () -> Unit,
    onCreate:  (name: String, description: String, endpoint: String) -> Unit
) {
    var name        by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var endpoint    by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = AiriTheme.surface,
        title = {
            Text(
                stringResource(R.string.skill_menu_create_with_airi),
                color      = AiriTheme.onBackground,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(R.string.skill_create_airi_desc),
                    color    = AiriTheme.onBackground.copy(0.6f),
                    fontSize = 13.sp
                )
                SkillTextField(stringResource(R.string.skill_name_label), name) { name = it }
                SkillTextField(stringResource(R.string.skill_description_label), description) { description = it }
                SkillTextField(stringResource(R.string.skill_endpoint_label), endpoint, keyboard = KeyboardType.Uri) { endpoint = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && endpoint.isNotBlank())
                        onCreate(name.trim(), description.trim(), endpoint.trim())
                },
                enabled = name.isNotBlank() && endpoint.isNotBlank()
            ) {
                Text(stringResource(R.string.create), color = CosmicAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = AiriTheme.onBackground.copy(0.55f))
            }
        }
    )
}
@Composable
private fun SkillTextField(
    label:         String,
    value:         String,
    keyboard:      KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 12.sp, color = AiriTheme.onBackground.copy(0.5f)) },
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = CosmicAccent,
            unfocusedBorderColor = AiriTheme.onSurface.copy(0.15f),
            focusedTextColor     = AiriTheme.onSurface,
            unfocusedTextColor   = AiriTheme.onSurface,
            focusedLabelColor    = CosmicAccent
        )
    )
}
/**
 * Parse a JSON skill definition exported from SkillBuilderScreen or the
 * AIRI skill repository format:
 * {
 *   "name": "…",
 *   "description": "…",
 *   "type": "API",           // optional, defaults to API
 *   "endpoint": "https://…",
 *   "method": "POST",        // optional
 *   "bodyTemplate": "…"      // optional
 * }
 */
private fun parseSkillJson(json: String): CustomSkill {
    val obj = JSONObject(json)
    return CustomSkill(
        id = obj.optString("id")
            .takeIf { it.matches(Regex("^[a-z][a-z0-9_-]{2,63}$")) }
            ?: "custom_${UUID.randomUUID().toString().replace("-", "")}",
        name        = obj.getString("name"),
        description = obj.optString("description", ""),
        type        = runCatching {
            SkillType.valueOf(obj.optString("type", "API").uppercase())
        }.getOrDefault(SkillType.API),
        config = SkillConfig(
            endpoint     = obj.optString("endpoint", ""),
            method       = obj.optString("method", "POST"),
            bodyTemplate = obj.optString("bodyTemplate", "{\"input\": \"{{input}}\"}")
        ),
        createdAt = System.currentTimeMillis()
    )
}

private fun CustomSkill.toManifest(): SkillManifest = SkillManifest(
    id = id,
    name = name,
    description = description,
    version = "1.0.0",
    author = "Local user",
    endpoint = config.endpoint,
    tools = listOf(
        SkillManifest.ToolDef(
            name = "run",
            description = description,
            parameters = mapOf("input" to SkillManifest.ParamDef(type = "string"))
        )
    )
)
