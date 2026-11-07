package com.airi.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ai.PromptDraftComposer
import com.airi.assistant.ai.PromptDraftInput
import com.airi.assistant.ai.PromptDraftKind
import com.airi.assistant.ai.PromptDraftTemplates
import com.airi.assistant.ui.theme.*
import java.util.UUID

private data class PromptCandidate(
    val id: String,
    val title: String,
    val description: String,
    val body: String,
)

private data class SavedPrompt(
    val id: String,
    val preferenceKey: String,
    val title: String,
    val body: String,
)

private const val PROMPT_LIBRARY_PREFS = "airi_prompt_library"
private const val SAVED_PROMPT_PREFIX = "saved_prompt_"

private fun loadSavedPrompts(context: Context): List<SavedPrompt> =
    context.getSharedPreferences(PROMPT_LIBRARY_PREFS, Context.MODE_PRIVATE)
        .all
        .mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            if (key == "pinned_prompt_id" || key == "pinned_prompt_body") return@mapNotNull null
            if (!key.startsWith(SAVED_PROMPT_PREFIX)) {
                // Older versions keyed saved prompts directly by their displayed title.
                return@mapNotNull SavedPrompt(
                    id = "legacy_$key",
                    preferenceKey = key,
                    title = key,
                    body = raw,
                )
            }
            val separator = raw.indexOf('\n')
            if (separator <= 0) return@mapNotNull null
            SavedPrompt(
                id = key,
                preferenceKey = key,
                title = raw.substring(0, separator),
                body = raw.substring(separator + 1),
            )
        }
        .sortedByDescending(SavedPrompt::id)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptBuilderScreen(
    onBack: () -> Unit,
    onUsePrompt: (String) -> Unit,
    onDelegateToAgent: (String) -> Unit = {},
) {
    val context = LocalContext.current
    var goal by rememberSaveable { mutableStateOf("") }
    var audience by rememberSaveable { mutableStateOf("") }
    var constraints by rememberSaveable { mutableStateOf("") }
    var selectedModelId by rememberSaveable { mutableStateOf("auto") }
    var selectedToneId by rememberSaveable { mutableStateOf("professional") }
    var selectedSkills by rememberSaveable { mutableStateOf(setOf("planning", "research")) }
    var selectedConnectors by rememberSaveable { mutableStateOf(setOf("web")) }
    var candidates by remember { mutableStateOf<List<PromptCandidate>>(emptyList()) }
    var chosen by remember { mutableStateOf<PromptCandidate?>(null) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var showReport by remember { mutableStateOf(false) }
    var savedPrompts by remember { mutableStateOf(loadSavedPrompts(context)) }
    var pinnedCandidateId by rememberSaveable {
        mutableStateOf(
            context.getSharedPreferences(PROMPT_LIBRARY_PREFS, Context.MODE_PRIVATE)
                .getString("pinned_prompt_id", null)
        )
    }

    val modelOptions = listOf(
        "auto" to stringResource(R.string.prompt_builder_model_auto),
        "fast" to stringResource(R.string.prompt_builder_model_fast),
        "reasoning" to stringResource(R.string.prompt_builder_model_reasoning),
    )
    val toneOptions = listOf(
        "professional" to stringResource(R.string.prompt_builder_tone_professional),
        "creative" to stringResource(R.string.prompt_builder_tone_creative),
        "concise" to stringResource(R.string.prompt_builder_tone_concise),
        "detailed" to stringResource(R.string.prompt_builder_tone_detailed),
    )
    val skillOptions = listOf(
        "planning" to stringResource(R.string.prompt_builder_skill_planning),
        "research" to stringResource(R.string.prompt_builder_skill_research),
        "coding" to stringResource(R.string.prompt_builder_skill_coding),
        "translation" to stringResource(R.string.prompt_builder_skill_translation),
        "memory" to stringResource(R.string.prompt_builder_skill_memory),
        "documents" to stringResource(R.string.prompt_builder_skill_documents),
    )
    val connectorOptions = listOf(
        "web" to stringResource(R.string.prompt_builder_connector_web),
        "google" to stringResource(R.string.prompt_builder_connector_google),
        "github" to stringResource(R.string.prompt_builder_connector_github),
        "telegram" to stringResource(R.string.prompt_builder_connector_telegram),
        "notion" to stringResource(R.string.prompt_builder_connector_notion),
        "files" to stringResource(R.string.prompt_builder_connector_files),
    )
    val selectedModel = modelOptions.firstOrNull { it.first == selectedModelId }?.second
        ?: modelOptions.first().second
    val selectedTone = toneOptions.firstOrNull { it.first == selectedToneId }?.second
        ?: toneOptions.first().second

    val templates = PromptDraftTemplates(
        focusedFormat = stringResource(R.string.prompt_builder_focused_template),
        agentWorkflowFormat = stringResource(R.string.prompt_builder_agent_template),
        unspecifiedAudience = stringResource(R.string.prompt_builder_default_audience),
        unspecifiedConstraints = stringResource(R.string.prompt_builder_default_constraints),
        noneSelected = stringResource(R.string.prompt_builder_none_selected),
    )
    val focusedTitle = stringResource(R.string.prompt_builder_variant_focused)
    val focusedDescription = stringResource(R.string.prompt_builder_variant_focused_desc)
    val agentTitle = stringResource(R.string.prompt_builder_variant_agent)
    val agentDescription = stringResource(R.string.prompt_builder_variant_agent_desc)

    fun clearGeneratedCandidates() {
        candidates = emptyList()
        chosen = null
        editingId = null
    }

    fun generate() {
        val drafts = PromptDraftComposer.generate(
            PromptDraftInput(
                goal = goal,
                audience = audience,
                constraints = constraints,
                modelPreference = selectedModel,
                tone = selectedTone,
                skills = selectedSkills.mapNotNull { id -> skillOptions.firstOrNull { it.first == id }?.second },
                connectors = selectedConnectors.mapNotNull { id -> connectorOptions.firstOrNull { it.first == id }?.second },
            ),
            templates,
        )
        candidates = drafts.map { draft ->
            val (title, description) = when (draft.kind) {
                PromptDraftKind.FOCUSED -> focusedTitle to focusedDescription
                PromptDraftKind.AGENT_WORKFLOW -> agentTitle to agentDescription
            }
            PromptCandidate(
                id = "${draft.kind.name}_${draft.body.hashCode()}",
                title = title,
                description = description,
                body = draft.body,
            )
        }
        chosen = candidates.firstOrNull()
        editingId = null
    }

    fun refreshSavedPrompts() {
        savedPrompts = loadSavedPrompts(context)
    }

    Scaffold(
        containerColor = AiriTheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.prompt_builder_title), fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(R.string.prompt_builder_subtitle),
                            fontSize = 11.sp,
                            color = AiriTheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = chosen != null,
                        onClick = {
                            chosen?.let { candidate ->
                                val preferences = context.getSharedPreferences(PROMPT_LIBRARY_PREFS, Context.MODE_PRIVATE)
                                if (pinnedCandidateId == candidate.id) {
                                    preferences.edit().remove("pinned_prompt_id").remove("pinned_prompt_body").apply()
                                    pinnedCandidateId = null
                                } else {
                                    preferences.edit()
                                        .putString("pinned_prompt_id", candidate.id)
                                        .putString("pinned_prompt_body", candidate.body)
                                        .apply()
                                    pinnedCandidateId = candidate.id
                                }
                            }
                        },
                    ) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = stringResource(R.string.prompt_pin),
                            tint = if (pinnedCandidateId == chosen?.id) CosmicAccent else AiriTheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showReport = true }) {
                        Icon(Icons.Outlined.Flag, stringResource(R.string.prompt_report))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 30.dp),
        ) {
            item {
                Surface(
                    shape = AIRIShapes.xl,
                    color = AiriTheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.outline.copy(alpha = .45f)),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.prompt_builder_goal), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                        OutlinedTextField(
                            value = goal,
                            onValueChange = { goal = it; clearGeneratedCandidates() },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            placeholder = { Text(stringResource(R.string.prompt_builder_goal_hint)) },
                            shape = AIRIShapes.md,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            PromptChoice(
                                text = selectedModel,
                                icon = Icons.Outlined.Memory,
                                options = modelOptions.map { it.second },
                                onSelect = { label ->
                                    selectedModelId = modelOptions.first { it.second == label }.first
                                    clearGeneratedCandidates()
                                },
                            )
                            PromptChoice(
                                text = selectedTone,
                                icon = Icons.Outlined.Tune,
                                options = toneOptions.map { it.second },
                                onSelect = { label ->
                                    selectedToneId = toneOptions.first { it.second == label }.first
                                    clearGeneratedCandidates()
                                },
                            )
                        }
                        OutlinedTextField(
                            value = audience,
                            onValueChange = { audience = it; clearGeneratedCandidates() },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text(stringResource(R.string.prompt_builder_audience)) },
                            leadingIcon = { Icon(Icons.Outlined.Groups, null) },
                            shape = AIRIShapes.md,
                        )
                        OutlinedTextField(
                            value = constraints,
                            onValueChange = { constraints = it; clearGeneratedCandidates() },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            label = { Text(stringResource(R.string.prompt_builder_constraints)) },
                            leadingIcon = { Icon(Icons.Outlined.Rule, null) },
                            shape = AIRIShapes.md,
                        )
                    }
                }
            }
            item {
                PromptSectionTitle(stringResource(R.string.prompt_builder_skills))
                ChoiceChips(skillOptions, selectedSkills) { id ->
                    selectedSkills = if (id in selectedSkills) selectedSkills - id else selectedSkills + id
                    clearGeneratedCandidates()
                }
            }
            item {
                PromptSectionTitle(stringResource(R.string.prompt_builder_connectors))
                ChoiceChips(connectorOptions, selectedConnectors) { id ->
                    selectedConnectors = if (id in selectedConnectors) selectedConnectors - id else selectedConnectors + id
                    clearGeneratedCandidates()
                }
            }
            item {
                Button(
                    onClick = { generate() },
                    enabled = goal.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = AIRIShapes.lg,
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.prompt_builder_generate), fontWeight = FontWeight.Bold)
                }
            }

            if (candidates.isNotEmpty()) {
                item { PromptSectionTitle(stringResource(R.string.prompt_builder_compare)) }
                items(candidates, key = PromptCandidate::id) { candidate ->
                    PromptCandidateCard(
                        candidate = candidate,
                        selected = chosen?.id == candidate.id,
                        editing = editingId == candidate.id,
                        onSelect = { chosen = candidate },
                        onEdit = { editingId = if (editingId == candidate.id) null else candidate.id },
                        onBodyChange = { updated ->
                            val changed = candidates.map { if (it.id == candidate.id) it.copy(body = updated) else it }
                            candidates = changed
                            chosen = changed.firstOrNull { it.id == candidate.id }
                        },
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { chosen?.let { onUsePrompt(it.body) } },
                            modifier = Modifier.weight(1f),
                            enabled = chosen != null,
                        ) {
                            Icon(Icons.Outlined.ContentCopy, null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.prompt_use))
                        }
                        Button(
                            onClick = {
                                chosen?.let {
                                    onDelegateToAgent(context.getString(R.string.prompt_builder_agent_review_request, it.body))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                            enabled = chosen != null,
                        ) {
                            Icon(Icons.Outlined.Psychology, null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.prompt_agent_review))
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                chosen?.let { candidate ->
                                    val id = "${System.currentTimeMillis().toString().padStart(13, '0')}_${UUID.randomUUID()}"
                                    context.getSharedPreferences(PROMPT_LIBRARY_PREFS, Context.MODE_PRIVATE)
                                        .edit()
                                        .putString("$SAVED_PROMPT_PREFIX$id", "${candidate.title}\n${candidate.body}")
                                        .apply()
                                    refreshSavedPrompts()
                                    Toast.makeText(context, R.string.prompt_saved, Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = chosen != null,
                        ) {
                            Icon(Icons.Outlined.BookmarkBorder, null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.prompt_save))
                        }
                        OutlinedButton(
                            onClick = {
                                chosen?.let { candidate ->
                                    val intent = Intent(Intent.ACTION_SEND)
                                        .setType("text/plain")
                                        .putExtra(Intent.EXTRA_TEXT, candidate.body)
                                    context.startActivity(Intent.createChooser(intent, context.getString(R.string.prompt_share)))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = chosen != null,
                        ) {
                            Icon(Icons.Outlined.Share, null)
                            Spacer(Modifier.width(5.dp))
                            Text(stringResource(R.string.prompt_share))
                        }
                    }
                }
            }

            if (savedPrompts.isNotEmpty()) {
                item { PromptSectionTitle(stringResource(R.string.prompt_builder_saved_prompts)) }
                items(savedPrompts, key = SavedPrompt::id) { saved ->
                    SavedPromptCard(
                        prompt = saved,
                        onUse = { onUsePrompt(saved.body) },
                        onDelete = {
                            context.getSharedPreferences(PROMPT_LIBRARY_PREFS, Context.MODE_PRIVATE)
                                .edit()
                                .remove(saved.preferenceKey)
                                .apply()
                            refreshSavedPrompts()
                        },
                    )
                }
            }
        }
    }

    if (showReport) {
        var report by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReport = false },
            title = { Text(stringResource(R.string.prompt_report_title)) },
            text = {
                OutlinedTextField(
                    value = report,
                    onValueChange = { report = it },
                    minLines = 4,
                    placeholder = { Text(stringResource(R.string.prompt_report_hint)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (report.isBlank()) {
                            Toast.makeText(context, R.string.prompt_report_empty, Toast.LENGTH_SHORT).show()
                        } else {
                            context.getSharedPreferences("airi_prompt_reports", Context.MODE_PRIVATE)
                                .edit()
                                .putString("report_${UUID.randomUUID()}", report.trim())
                                .apply()
                            showReport = false
                            Toast.makeText(context, R.string.prompt_report_saved, Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text(stringResource(R.string.prompt_send_report)) }
            },
            dismissButton = {
                TextButton(onClick = { showReport = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun PromptSectionTitle(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun RowScope.PromptChoice(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.weight(1f)) {
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { expanded = true },
            shape = AIRIShapes.md,
            color = AiriTheme.surfaceVariant,
            border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.outline.copy(alpha = .4f)),
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Outlined.ExpandMore, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ChoiceChips(
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
        items(options, key = { it.first }) { (id, label) ->
            FilterChip(
                selected = id in selected,
                onClick = { onToggle(id) },
                label = { Text(label, fontSize = 11.sp) },
                shape = AIRIShapes.pill,
            )
        }
    }
}

@Composable
private fun PromptCandidateCard(
    candidate: PromptCandidate,
    selected: Boolean,
    editing: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onBodyChange: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        shape = AIRIShapes.xl,
        color = if (selected) CosmicAccent.copy(alpha = .10f) else AiriTheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) CosmicAccent else AiriTheme.outline.copy(alpha = .35f),
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(if (selected) CosmicAccent else AiriTheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (selected) Icons.Outlined.Check else Icons.Outlined.AutoAwesome,
                        null,
                        tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(candidate.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, stringResource(R.string.prompt_edit)) }
            }
            Text(candidate.description, color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            if (editing) {
                OutlinedTextField(candidate.body, onBodyChange, Modifier.fillMaxWidth(), minLines = 8)
            } else {
                Text(candidate.body, color = AiriTheme.onSurfaceVariant, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun SavedPromptCard(prompt: SavedPrompt, onUse: () -> Unit, onDelete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AIRIShapes.lg,
        color = AiriTheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, AiriTheme.outline.copy(alpha = .35f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(prompt.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete))
                }
            }
            Text(
                prompt.body,
                color = AiriTheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 18.sp,
            )
            TextButton(onClick = onUse) {
                Icon(Icons.Outlined.Edit, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.prompt_use))
            }
        }
    }
}
