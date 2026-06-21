package com.airi.assistant.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.skills.OfficialSkillLibrary
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
    onEdit:   (String) -> Unit
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

    fun reload() {
        officialSkills = skillRegistry.getAllSkillInfos().filter { it.author == "AIRI Official" }
        customSkills   = repository.getAllSkills()
    }

    // ── Storage picker (JSON skill file) ──────────────────────────────────────
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            runCatching {
                val json = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.readText() ?: error("Empty file")
                val skill = parseSkillJson(json)
                repository.saveSkill(skill)
                withContext(Dispatchers.Main) { reload(); importSource = null }
            }.onFailure { e ->
                withContext(Dispatchers.Main) {
                    errorMessage = context.getString(R.string.skill_import_failed, e.message ?: "")
                }
            }
        }
    }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background.copy(alpha = 0.95f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = AiriTheme.onBackground
                        )
                    }
                },
                title = {
                    Column {
                        Text(
                            stringResource(R.string.skill_title),
                            color = AiriTheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        val activeCount = officialSkills.count { it.isEnabled && it.isConnected } + customSkills.size
                        val totalCount  = officialSkills.size + customSkills.size
                        Text(
                            "$totalCount skills · $activeCount active",
                            color = AiriTheme.onBackground.copy(0.45f),
                            fontSize = 11.sp
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_add_skill),
                                tint = CosmicAccent
                            )
                        }
                        DropdownMenu(
                            expanded         = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            AddOption(Icons.Outlined.Edit, stringResource(R.string.skill_menu_create)) {
                                showAddMenu = false; onCreate()
                            }
                            AddOption(Icons.Outlined.FolderOpen, stringResource(R.string.skill_menu_import_storage)) {
                                showAddMenu = false
                                importSource = ImportSource.STORAGE
                                filePicker.launch("application/json")
                            }
                            AddOption(Icons.Outlined.Code, stringResource(R.string.skill_menu_import_github)) {
                                showAddMenu = false; importSource = ImportSource.GITHUB
                            }
                            AddOption(Icons.Outlined.AutoAwesome, stringResource(R.string.skill_menu_create_with_airi)) {
                                showAddMenu = false; importSource = ImportSource.AI
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── Error banner ───────────────────────────────────────────────────
            errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SemanticError.copy(0.15f))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Warning, null, tint = SemanticError, modifier = Modifier.size(16.dp))
                    Text(msg, color = SemanticError, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { errorMessage = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, null, tint = SemanticError)
                    }
                }
            }

            // ── GitHub import dialog (Phase 6: uses GitHubSkillImporter) ──────
            if (importSource == ImportSource.GITHUB) {
                GitHubImportDialog(
                    isImporting = isImporting,
                    onDismiss   = { importSource = null },
                    onImport    = { rawUrl ->
                        scope.launch {
                            withContext(Dispatchers.Main) { isImporting = true }
                            val result = withContext(Dispatchers.IO) {
                                GitHubSkillImporter.importFromUrl(rawUrl)
                            }
                            withContext(Dispatchers.Main) {
                                isImporting = false
                                if (result.success && result.skill != null) {
                                    repository.saveSkill(result.skill)
                                    reload()
                                    importSource = null
                                    if (result.warnings.isNotEmpty()) {
                                        errorMessage = "Imported with ${result.warnings.size} warning(s): " +
                                            result.warnings.take(2).joinToString("; ")
                                    }
                                } else {
                                    errorMessage = context.getString(
                                        R.string.skill_import_github_failed,
                                        result.errors.take(3).joinToString("; ")
                                    )
                                    importSource = null
                                }
                            }
                        }
                    }
                )
            }

            // ── AI create dialog ───────────────────────────────────────────────
            if (importSource == ImportSource.AI) {
                AiSkillCreateDialog(
                    onDismiss = { importSource = null },
                    onCreate  = { name, description, endpoint ->
                        val skill = CustomSkill(
                            id          = UUID.randomUUID().toString(),
                            name        = name,
                            description = description,
                            type        = SkillType.API,
                            config      = SkillConfig(
                                endpoint     = endpoint,
                                method       = "POST",
                                bodyTemplate = "{\"input\": \"{{input}}\"}"
                            ),
                            createdAt   = System.currentTimeMillis()
                        )
                        repository.saveSkill(skill)
                        reload()
                        importSource = null
                    }
                )
            }

            // ── Skill list: Official + Custom sections ─────────────────────────
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {

                // ── Official Skills ────────────────────────────────────────────
                item(key = "official_header") {
                    Text(
                        "Official Skills",
                        color      = AiriTheme.onBackground.copy(0.45f),
                        fontSize   = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier   = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }

                items(officialSkills, key = { "official_${it.name}" }) { info ->
                    OfficialSkillCard(
                        info     = info,
                        onToggle = { enabled ->
                            skillRegistry.setSkillEnabled(info.name, enabled)
                            reload()
                        }
                    )
                }

                // ── Custom Skills ──────────────────────────────────────────────
                item(key = "custom_header") {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "My Custom Skills",
                            color      = AiriTheme.onBackground.copy(0.45f),
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (customSkills.isEmpty()) {
                            TextButton(
                                onClick = onCreate,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    stringResource(R.string.skill_create_button),
                                    color    = CosmicAccent,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                if (customSkills.isEmpty()) {
                    item(key = "custom_empty") {
                        Text(
                            stringResource(R.string.skill_no_skills_desc),
                            color    = AiriTheme.onBackground.copy(0.35f),
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    items(customSkills, key = { it.id }) { skill ->
                        SkillCard(
                            skill    = skill,
                            onClick  = { onEdit(skill.id) },
                            onDelete = {
                                repository.deleteSkill(skill.id)
                                reload()
                            }
                        )
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun AddOption(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        leadingIcon = { Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp)) },
        text = { Text(label, fontSize = 14.sp) },
        onClick = onClick
    )
}

// ── Official Skill Card (Phase 4) ─────────────────────────────────────────────

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
private fun OfficialSkillCard(
    info:     SkillRegistry.SkillInfo,
    onToggle: (Boolean) -> Unit
) {
    val entry = remember(info.name) { OfficialSkillLibrary.ALL.firstOrNull { it.manifest.id == info.name } }
    val displayName = entry?.manifest?.name
        ?: info.name.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    val emoji          = entry?.manifest?.iconEmoji ?: "🔧"
    val needsConnector = !info.isConnected

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AiriTheme.surface)
            .border(1.dp, Color.White.copy(if (needsConnector) 0.04f else 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(emoji, fontSize = 22.sp, modifier = Modifier.width(38.dp))

        Column(
            modifier              = Modifier.weight(1f),
            verticalArrangement   = Arrangement.spacedBy(3.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    displayName,
                    color      = if (needsConnector) AiriTheme.onBackground.copy(0.45f)
                                 else AiriTheme.onBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                if (needsConnector) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(0.07f)
                    ) {
                        Text(
                            "Connector required",
                            color    = AiriTheme.onBackground.copy(0.35f),
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = CosmicAccent.copy(alpha = 0.14f)
                    ) {
                        Text(
                            "OFFICIAL",
                            color    = CosmicAccent,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Text(
                info.description,
                color    = AiriTheme.onBackground.copy(if (needsConnector) 0.35f else 0.5f),
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (info.author.isNotBlank()) {
                Text(
                    "v${info.version} · ${info.author}",
                    color    = AiriTheme.onBackground.copy(0.25f),
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.width(8.dp))

        if (!needsConnector) {
            Switch(
                checked         = info.isEnabled,
                onCheckedChange = onToggle,
                colors          = SwitchDefaults.colors(
                    checkedThumbColor   = Color.White,
                    checkedTrackColor   = CosmicAccent,
                    uncheckedThumbColor = AiriTheme.onBackground.copy(0.35f),
                    uncheckedTrackColor = Color.White.copy(0.1f)
                )
            )
        }
    }
}

// ── Custom Skill Card (unchanged from original) ───────────────────────────────

@Composable
private fun SkillCard(
    skill:    CustomSkill,
    onClick:  () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AiriTheme.surface)
            .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier              = Modifier.weight(1f),
            verticalArrangement   = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    skill.name,
                    color      = AiriTheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 15.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = CosmicAccent.copy(alpha = 0.14f)
                ) {
                    Text(
                        skill.type.name,
                        color    = CosmicAccent,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                skill.description,
                color    = AiriTheme.onBackground.copy(0.55f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                skill.config.endpoint,
                color    = AiriTheme.onBackground.copy(0.3f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.cd_delete),
                tint = Color(0xFFFF6B6B)
            )
        }
    }
}

// ── GitHub Import Dialog ──────────────────────────────────────────────────────

@Composable
private fun GitHubImportDialog(
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var rawUrl by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color(0xFF111525),
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
                        unfocusedBorderColor = Color.White.copy(0.15f),
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White
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

// ── AI Create Dialog ──────────────────────────────────────────────────────────

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
        containerColor   = Color(0xFF111525),
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

// ── Shared TextField ──────────────────────────────────────────────────────────

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
            unfocusedBorderColor = Color.White.copy(0.15f),
            focusedTextColor     = Color.White,
            unfocusedTextColor   = Color.White,
            focusedLabelColor    = CosmicAccent
        )
    )
}

// ── JSON skill file parser ────────────────────────────────────────────────────

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
        id          = UUID.randomUUID().toString(),
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
