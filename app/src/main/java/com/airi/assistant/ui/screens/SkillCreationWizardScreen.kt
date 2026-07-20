package com.airi.assistant.ui.screens

import com.airi.assistant.R
import androidx.compose.ui.res.stringResource
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.skills.SkillManifest
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import kotlinx.coroutines.launch
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicAccentAlt
import androidx.compose.material3.MaterialTheme
import com.airi.assistant.ui.theme.SemanticError
import java.util.UUID
private data class WizardParam(
    val id:          String  = UUID.randomUUID().toString(),
    var name:        String  = "",
    var type:        String  = "string",
    var description: String  = "",
    var required:    Boolean = true
)

private data class WizardTool(
    val id:          String  = UUID.randomUUID().toString(),
    var name:        String  = "",
    var description: String  = "",
    val params:      SnapshotStateList<WizardParam> = androidx.compose.runtime.snapshots.SnapshotStateList()
)

private val CATEGORIES = listOf(
    "UTILITY", "SEARCH", "PRODUCTIVITY", "COMMUNICATION",
    "AI", "DEVELOPER", "MEDIA", "FINANCE", "HEALTH", "OTHER"
)

private val ICON_EMOJIS = listOf(
    "⚙", "⊙", "⊕", "▤", "⌨", "◳", "◈", "◎",
    "◉", "◫", "▣", "◉", "⊞", "◧", "▤", "⊡"
)

private val PARAM_TYPES = listOf("string", "integer", "boolean", "number", "array", "object")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillCreationWizardScreen(onBack: () -> Unit) {

    val context   = LocalContext.current
    val snackHost = remember { SnackbarHostState() }
    val scope     = rememberCoroutineScope()

    var step by remember { mutableStateOf(0) }
    var skillId      by remember { mutableStateOf("") }
    var name         by remember { mutableStateOf("") }
    var description  by remember { mutableStateOf("") }
    var version      by remember { mutableStateOf("1.0.0") }
    var author       by remember { mutableStateOf("") }
    var category     by remember { mutableStateOf("UTILITY") }
    var iconEmoji    by remember { mutableStateOf("⚙") }
    var tags         by remember { mutableStateOf("") }
    var repositoryUrl by remember { mutableStateOf("") }
    var license      by remember { mutableStateOf("MIT") }
    val tools = remember { mutableStateListOf<WizardTool>() }
    var memoryAccess    by remember { mutableStateOf(SkillMemoryAccess.NONE) }
    var modelAccess     by remember { mutableStateOf(SkillModelAccess.NONE) }
    var dependencies    by remember { mutableStateOf("") }
    var generatedJson   by remember { mutableStateOf("") }
    var showShareDialog by remember { mutableStateOf(false) }

    fun buildManifest(): SkillManifest {
        val resolvedId = skillId.trim()
            .ifBlank { name.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_") }
        return SkillManifest(
            id           = resolvedId,
            name         = name.trim(),
            description  = description.trim(),
            version      = version.trim().ifBlank { "1.0.0" },
            author       = author.trim().ifBlank { "Unknown" },
            category     = category,
            iconEmoji    = iconEmoji,
            memoryAccess = memoryAccess,
            modelAccess  = modelAccess,
            dependencies = dependencies.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            tools        = tools.map { t ->
                SkillManifest.ToolDef(
                    name        = t.name.trim(),
                    description = t.description.trim(),
                    parameters  = t.params
                        .filter { it.name.isNotBlank() }
                        .associate { p ->
                            p.name.trim() to SkillManifest.ParamDef(
                                type        = p.type,
                                description = p.description,
                                required    = p.required
                            )
                        }
                )
            },
            tags          = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
            repositoryUrl = repositoryUrl.trim().ifBlank { null },
            license       = license.trim().ifBlank { "MIT" }
        )
    }

    fun validate(): String? {
        if (name.isBlank()) return "Skill name is required"
        if (description.isBlank()) return "Description is required"
        if (description.length < 10) return "Description must be at least 10 characters"
        val semver = Regex("""^\d+\.\d+\.\d+(-[\w.]+)?$""")
        if (!semver.matches(version.trim())) return "Version must be semver (e.g. 1.0.0)"
        if (author.isBlank()) return "Author is required"
        return null
    }

    fun goToPreview() {
        val err = validate()
        if (err != null) {
            scope.launch { snackHost.showSnackbar(err) }
            return
        }
        generatedJson = buildManifest().toJson().toString(2)
        step = 3
    }

    val stepTitles = listOf("Identity", "Tools", "Permissions", "Preview & Export")

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                navigationIcon = {
                    IconButton(onClick = { if (step > 0) step-- else onBack() }) {
                        Icon(
                            if (step > 0) Icons.Default.ArrowBack else Icons.Default.Close,
                            contentDescription = null, tint = AiriTheme.onBackground
                        )
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.skill_wizard_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                        Text(stringResource(R.string.skill_wizard_step, step + 1, stepTitles[step]), fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (step < 3) {
                        TextButton(
                            onClick = {
                                if (step == 2) goToPreview() else step++
                            }
                        ) {
                            Text(if (step == 2) "Preview" else "Next", color = CosmicAccent, fontWeight = FontWeight.Bold)
                            Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp), tint = CosmicAccent)
                        }
                    }
                }
            )
        }
    ) { padding ->

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            WizardStepIndicator(currentStep = step, totalSteps = 4, titles = stepTitles)
            AnimatedContent(
                targetState  = step,
                transitionSpec = {
                    if (targetState > initialState)
                        slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
                    else
                        slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                },
                label = "step_content"
            ) { currentStep ->
                when (currentStep) {
                    0 -> IdentityStep(
                        skillId       = skillId,      onSkillIdChange      = { skillId = it },
                        name          = name,         onNameChange         = { name = it },
                        description   = description,  onDescriptionChange  = { description = it },
                        version       = version,      onVersionChange      = { version = it },
                        author        = author,       onAuthorChange       = { author = it },
                        category      = category,     onCategoryChange     = { category = it },
                        iconEmoji     = iconEmoji,    onIconChange         = { iconEmoji = it },
                        tags          = tags,         onTagsChange         = { tags = it },
                        repositoryUrl = repositoryUrl, onRepoUrlChange     = { repositoryUrl = it },
                        license       = license,      onLicenseChange      = { license = it },
                        onNext        = { step = 1 }
                    )
                    1 -> ToolsStep(tools = tools, onNext = { step = 2 })
                    2 -> PermissionsStep(
                        memoryAccess = memoryAccess, onMemoryChange = { memoryAccess = it },
                        modelAccess  = modelAccess,  onModelChange  = { modelAccess  = it },
                        dependencies = dependencies, onDepsChange   = { dependencies = it },
                        onPreview    = { goToPreview() }
                    )
                    3 -> PreviewExportStep(
                        json          = generatedJson,
                        onRegenerate  = { generatedJson = buildManifest().toJson().toString(2) },
                        onCopy        = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("skill.json", generatedJson))
                            scope.launch { snackHost.showSnackbar("Copied to clipboard") }
                        },
                        onShare       = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "skill.json — ${name.trim()}")
                                putExtra(Intent.EXTRA_TEXT, generatedJson)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share skill.json"))
                        },
                        onEdit        = { step = 0 }
                    )
                }
            }
        }
    }
}
@Composable
private fun IdentityStep(
    skillId: String, onSkillIdChange: (String) -> Unit,
    name: String, onNameChange: (String) -> Unit,
    description: String, onDescriptionChange: (String) -> Unit,
    version: String, onVersionChange: (String) -> Unit,
    author: String, onAuthorChange: (String) -> Unit,
    category: String, onCategoryChange: (String) -> Unit,
    iconEmoji: String, onIconChange: (String) -> Unit,
    tags: String, onTagsChange: (String) -> Unit,
    repositoryUrl: String, onRepoUrlChange: (String) -> Unit,
    license: String, onLicenseChange: (String) -> Unit,
    onNext: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WizardSectionLabel("Basic Information")
            Spacer(Modifier.height(8.dp))
            WizardField(name, onNameChange, "Skill Name *", "e.g. Web Search")
        }
        item {
            WizardField(
                skillId, onSkillIdChange, "Skill ID",
                "machine_readable_id (auto-generated from name if blank)"
            )
        }
        item {
            WizardField(
                description, onDescriptionChange, "Description *",
                "What does this skill do? (≥10 chars)", minLines = 3
            )
        }

        item {
            WizardSectionLabel("Icon")
            Spacer(Modifier.height(8.dp))
            EmojiPicker(selected = iconEmoji, onSelect = onIconChange)
        }

        item {
            WizardSectionLabel("Category")
            Spacer(Modifier.height(8.dp))
            CategorySelector(selected = category, onSelect = onCategoryChange)
        }

        item {
            WizardSectionLabel("Publishing Metadata")
            Spacer(Modifier.height(8.dp))
            WizardField(author, onAuthorChange, "Author *", "Your name or organisation")
        }
        item { WizardField(version, onVersionChange, "Version *", "1.0.0 — must be semver") }
        item { WizardField(license, onLicenseChange, "License", "MIT, Apache-2.0, etc.") }
        item { WizardField(repositoryUrl, onRepoUrlChange, "Repository URL", "https://github.com/you/skill-repo") }
        item { WizardField(tags, onTagsChange, "Tags", "comma-separated: search, web, news") }

        item {
            Button(
                onClick  = onNext,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = RoundedCornerShape(14.dp),
                enabled  = name.isNotBlank() && description.length >= 10 && author.isNotBlank()
            ) {
                Text(stringResource(R.string.skill_wizard_next_tools), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}
@Composable
private fun ToolsStep(
    tools: SnapshotStateList<WizardTool>,
    onNext: () -> Unit
) {
    var expandedTool by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WizardSectionLabel("Tool Definitions")
            Spacer(Modifier.height(4.dp))
            Text(
                "Define the tools the agent loop can call. Each tool maps to one action your skill performs.",
                fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
            )
        }

        itemsIndexed(tools, key = { _, t -> t.id }) { index, tool ->
            ToolCard(
                tool       = tool,
                expanded   = expandedTool == tool.id,
                onExpand   = { expandedTool = if (expandedTool == tool.id) null else tool.id },
                onRemove   = { tools.removeAt(index) }
            )
        }

        item {
            OutlinedButton(
                onClick  = {
                    val t = WizardTool()
                    tools.add(t)
                    expandedTool = t.id
                },
                modifier = Modifier.fillMaxWidth(),
                border   = androidx.compose.foundation.BorderStroke(1.dp, CosmicAccent.copy(0.5f)),
                shape    = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp), tint = CosmicAccent)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.skill_wizard_add_tool), color = CosmicAccent)
            }
        }

        item {
            Button(
                onClick  = onNext,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Text(stringResource(R.string.skill_wizard_next_permissions), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.ArrowForward, null, Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool:     WizardTool,
    expanded: Boolean,
    onExpand: () -> Unit,
    onRemove: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = RoundedCornerShape(12.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Build, null, Modifier.size(18.dp), tint = CosmicAccent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tool.name.ifBlank { "Unnamed Tool" },
                        fontWeight = FontWeight.SemiBold,
                        color = AiriTheme.onBackground,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    if (tool.description.isNotBlank()) {
                        Text(tool.description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = SemanticError, modifier = Modifier.size(16.dp))
                }
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    null, Modifier.size(20.dp), tint = AiriTheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Divider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    WizardField(tool.name, { tool.name = it }, "Tool Name *", "e.g. web_search")
                    WizardField(tool.description, { tool.description = it }, "Tool Description *", "What does this tool do?")

                    Text(stringResource(R.string.skill_wizard_parameters), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CosmicAccent)

                    tool.params.forEachIndexed { i, param ->
                        ParamRow(param = param, onRemove = { tool.params.removeAt(i) })
                    }

                    TextButton(
                        onClick = { tool.params.add(WizardParam()) }
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(16.dp), tint = CosmicAccent)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.skill_wizard_add_parameter), color = CosmicAccent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParamRow(param: WizardParam, onRemove: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surface),
        shape  = RoundedCornerShape(8.dp)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                WizardField(param.name, { param.name = it }, "Name *", "", modifier = Modifier.weight(1f))
                var typeExpanded by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(
                        onClick = { typeExpanded = true },
                        modifier = Modifier.height(56.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(param.type, color = AiriTheme.onBackground, fontSize = 12.sp)
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(16.dp), tint = AiriTheme.onBackground)
                    }
                    DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        PARAM_TYPES.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t, fontSize = 13.sp) },
                                onClick = { param.type = t; typeExpanded = false }
                            )
                        }
                    }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Delete, null, tint = SemanticError, modifier = Modifier.size(16.dp))
                }
            }
            WizardField(param.description, { param.description = it }, "Description", "What this parameter does")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = param.required,
                    onCheckedChange = { param.required = it },
                    colors = CheckboxDefaults.colors(checkedColor = CosmicAccent)
                )
                Text(stringResource(R.string.skill_wizard_required), fontSize = 13.sp, color = AiriTheme.onBackground)
            }
        }
    }
}
@Composable
private fun PermissionsStep(
    memoryAccess: SkillMemoryAccess, onMemoryChange: (SkillMemoryAccess) -> Unit,
    modelAccess:  SkillModelAccess,  onModelChange:  (SkillModelAccess)  -> Unit,
    dependencies: String,            onDepsChange:   (String) -> Unit,
    onPreview:    () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            WizardSectionLabel("Memory Access")
            Spacer(Modifier.height(4.dp))
            Text(
                "Controls whether this skill can read or write to AIRI's persistent memory.",
                fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            SkillMemoryAccess.values().forEach { level ->
                AccessLevelRow(
                    label    = level.label,
                    sublabel = when (level) {
                        SkillMemoryAccess.NONE       -> "Skill cannot access memory"
                        SkillMemoryAccess.READ_ONLY  -> "Skill can read past messages only"
                        SkillMemoryAccess.READ_WRITE -> "Skill can read and record new memories"
                        SkillMemoryAccess.FULL_ACCESS -> "Skill can read, write, and delete memories"
                    },
                    selected = memoryAccess == level,
                    onClick  = { onMemoryChange(level) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        item {
            WizardSectionLabel("Model Access")
            Spacer(Modifier.height(4.dp))
            Text(
                "Controls whether this skill can call the active LLM for additional inference.",
                fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            SkillModelAccess.values().forEach { level ->
                AccessLevelRow(
                    label    = level.label,
                    sublabel = when (level) {
                        SkillModelAccess.NONE              -> "Skill does not call the model"
                        SkillModelAccess.CHAT              -> "Skill may call model for text completion"
                        SkillModelAccess.CHAT_WITH_ROUTING -> "Skill may call model with full routing decisions"
                    },
                    selected = modelAccess == level,
                    onClick  = { onModelChange(level) }
                )
                Spacer(Modifier.height(6.dp))
            }
        }

        item {
            WizardSectionLabel("Dependencies")
            Spacer(Modifier.height(8.dp))
            WizardField(
                dependencies, onDepsChange,
                "Skill Dependencies",
                "Comma-separated skill IDs this skill depends on (e.g. web_search, translator)"
            )
        }

        item {
            Button(
                onClick  = onPreview,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Code, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.skill_wizard_generate), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AccessLevelRow(
    label:    String,
    sublabel: String,
    selected: Boolean,
    onClick:  () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CosmicAccent.copy(0.12f) else AiriTheme.surfaceVariant)
            .border(1.dp, if (selected) CosmicAccent.copy(0.5f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick  = onClick,
            colors   = RadioButtonDefaults.colors(selectedColor = CosmicAccent)
        )
        Column {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                color = if (selected) CosmicAccent else AiriTheme.onBackground)
            Text(sublabel, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
        }
    }
}
@Composable
private fun PreviewExportStep(
    json:         String,
    onRegenerate: () -> Unit,
    onCopy:       () -> Unit,
    onShare:      () -> Unit,
    onEdit:       () -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.skill_wizard_output_title), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = AiriTheme.onBackground)
            Text(
                "Your skill manifest is ready. Copy it, share it, or paste it into the Publish tab of the Marketplace.",
                fontSize = 13.sp, color = AiriTheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportButton(Icons.Default.ContentCopy, "Copy",  CosmicAccent,     onClick = onCopy,       Modifier.weight(1f))
                ExportButton(Icons.Default.Share,       "Share", CosmicAccentAlt,  onClick = onShare,      Modifier.weight(1f))
                ExportButton(Icons.Default.Edit,        "Edit",  AiriTheme.onSurfaceVariant, onClick = onEdit, Modifier.weight(1f))
            }
        }

        Card(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape  = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("skill.json", fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFF8B949E))
                IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Refresh, "Regenerate", tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                }
            }
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(
                    json,
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 12.sp,
                    color      = Color(0xFFE6EDF3),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ExportButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick  = onClick,
        modifier = modifier.height(44.dp),
        border   = androidx.compose.foundation.BorderStroke(1.dp, color.copy(0.5f)),
        shape    = RoundedCornerShape(10.dp)
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
@Composable
private fun WizardStepIndicator(currentStep: Int, totalSteps: Int, titles: List<String>) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { i ->
            val done    = i < currentStep
            val current = i == currentStep
            val color   = when { done || current -> CosmicAccent; else -> AiriTheme.onSurfaceVariant.copy(0.3f) }

            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (current) CosmicAccent else if (done) CosmicAccent.copy(0.25f) else AiriTheme.surfaceVariant)
                    .border(1.dp, color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = CosmicAccent)
                } else {
                    Text("${i + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        color = if (current) MaterialTheme.colorScheme.onSurface else AiriTheme.onSurfaceVariant)
                }
            }

            if (i < totalSteps - 1) {
                Divider(
                    modifier = Modifier.weight(1f),
                    color    = if (i < currentStep) CosmicAccent.copy(0.5f) else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun WizardSectionLabel(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = CosmicAccent)
}

@Composable
private fun WizardField(
    value:         String,
    onValueChange: (String) -> Unit,
    label:         String,
    placeholder:   String = "",
    minLines:      Int    = 1,
    modifier:      Modifier = Modifier.fillMaxWidth()
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 13.sp) },
        placeholder   = if (placeholder.isNotBlank()) ({ Text(placeholder, color = AiriTheme.onSurfaceVariant.copy(0.5f), fontSize = 12.sp) }) else null,
        modifier      = modifier,
        minLines      = minLines,
        colors        = OutlinedTextFieldDefaults.colors(
            focusedBorderColor   = CosmicAccent,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedLabelColor    = CosmicAccent
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategorySelector(selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(CATEGORIES.size) { i ->
            val cat  = CATEGORIES[i]
            val active = cat == selected
            FilterChip(
                selected = active,
                onClick  = { onSelect(cat) },
                label    = { Text(cat, fontSize = 12.sp) },
                colors   = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = CosmicAccent.copy(0.2f),
                    selectedLabelColor     = CosmicAccent
                )
            )
        }
    }
}

@Composable
private fun EmojiPicker(selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(ICON_EMOJIS.size) { i ->
            val emoji  = ICON_EMOJIS[i]
            val active = emoji == selected
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) CosmicAccent.copy(0.2f) else AiriTheme.surfaceVariant)
                    .border(1.dp, if (active) CosmicAccent else MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    .clickable { onSelect(emoji) },
                contentAlignment = Alignment.Center
            ) {
                Text(emoji, fontSize = 20.sp)
            }
        }
    }
}
