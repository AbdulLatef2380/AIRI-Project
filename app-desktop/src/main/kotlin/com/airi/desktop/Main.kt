package com.airi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.airi.core.models.ModelAvailability
import com.airi.core.skills.SkillAvailability
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

fun main() = application {
    val agent = remember { DesktopAgent() }
    val preferencesStore = remember { DesktopPreferencesStore() }
    var preferences by remember { mutableStateOf(preferencesStore.load()) }
    var messages by remember { mutableStateOf(agent.history()) }
    var stagedAttachments by remember { mutableStateOf(agent.stagedAttachments()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "AIRI Desktop",
        state = rememberWindowState(width = 1240.dp, height = 820.dp)
    ) {
        window.minimumSize = Dimension(920, 640)
        AiriDesktopApp(
            messages = messages,
            stagedAttachments = stagedAttachments,
            selectedModelName = agent.selectedModel()?.displayName,
            models = agent.availableModels(),
            selectedSkillName = agent.selectedSkill()?.displayName,
            skills = agent.availableSkills(),
            statusMessage = statusMessage,
            showCapabilityHints = preferences.showCapabilityHints,
            onToggleCapabilityHints = {
                preferences = preferences.copy(showCapabilityHints = !preferences.showCapabilityHints)
                preferencesStore.save(preferences)
            },
            onSubmit = { input ->
                agent.submit(input)?.let { reply ->
                    messages = agent.history()
                    stagedAttachments = agent.stagedAttachments()
                    statusMessage = reply.message.body
                }
            },
            onSelectModel = { modelId ->
                statusMessage = when (val result = agent.selectModel(modelId)) {
                    is com.airi.core.models.ModelSelectionResult.Selected -> "Selected ${result.model.displayName}."
                    is com.airi.core.models.ModelSelectionResult.Rejected -> result.reason
                }
            },
            onSelectSkill = { skillId ->
                statusMessage = when (val result = agent.selectSkill(skillId)) {
                    is com.airi.core.skills.SkillSelectionResult.Selected -> "Selected ${result.skill.displayName}."
                    is com.airi.core.skills.SkillSelectionResult.Rejected -> result.reason
                }
            },
            onAddAttachment = {
                chooseDesktopFile()?.let { source ->
                    statusMessage = when (val result = agent.stageAttachment(source)) {
                        is DesktopAttachmentResult.Accepted -> "Added ${result.attachment.displayName}."
                        is DesktopAttachmentResult.Rejected -> result.reason
                    }
                    stagedAttachments = agent.stagedAttachments()
                }
            },
            onRemoveAttachment = { attachmentId ->
                agent.discardStagedAttachment(attachmentId)
                stagedAttachments = agent.stagedAttachments()
            },
            onClear = {
                agent.clearHistory()
                messages = emptyList()
                stagedAttachments = emptyList()
                statusMessage = null
            }
        )
    }
}

@Composable
private fun AiriDesktopApp(
    messages: List<DesktopMessage>,
    stagedAttachments: List<DesktopAttachment>,
    selectedModelName: String?,
    models: List<com.airi.core.models.ModelDescriptor>,
    selectedSkillName: String?,
    skills: List<com.airi.core.skills.SkillDescriptor>,
    statusMessage: String?,
    showCapabilityHints: Boolean,
    onToggleCapabilityHints: () -> Unit,
    onSubmit: (String) -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectSkill: (String) -> Unit,
    onAddAttachment: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onClear: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var skillMenuExpanded by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val composerFocus = remember { FocusRequester() }

    fun submitInput() {
        if (input.isBlank()) return
        onSubmit(input)
        input = ""
    }

    fun applyCommand(command: DesktopCommand): Boolean = when (command) {
        DesktopCommand.START_NEW_DRAFT -> {
            input = ""
            composerFocus.requestFocus()
            true
        }
        DesktopCommand.FOCUS_COMPOSER -> {
            composerFocus.requestFocus()
            true
        }
        DesktopCommand.DISMISS_TRANSIENT_UI -> {
            modelMenuExpanded = false
            skillMenuExpanded = false
            true
        }
    }

    MaterialTheme(colorScheme = AiriDesktopColorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val keyName = when (event.key) {
                        Key.N -> "N"
                        Key.K -> "K"
                        Key.Escape -> "ESCAPE"
                        else -> return@onPreviewKeyEvent false
                    }
                    DesktopShortcutPolicy.resolve(keyName, event.isCtrlPressed)?.let(::applyCommand) ?: false
                }
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val compact = maxWidth < 1040.dp
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (compact) DesktopSpacing.large else DesktopSpacing.page),
                    verticalArrangement = Arrangement.spacedBy(DesktopSpacing.medium)
                ) {
                    Header(messages.isNotEmpty(), compact, showCapabilityHints, onToggleCapabilityHints, onClear)
                    CapabilityBar(
                        compact = compact,
                        showCapabilityHints = showCapabilityHints,
                        selectedModelName = selectedModelName,
                        models = models,
                        modelMenuExpanded = modelMenuExpanded,
                        onModelMenuExpandedChange = { modelMenuExpanded = it },
                        onSelectModel = onSelectModel,
                        selectedSkillName = selectedSkillName,
                        skills = skills,
                        skillMenuExpanded = skillMenuExpanded,
                        onSkillMenuExpandedChange = { skillMenuExpanded = it },
                        onSelectSkill = onSelectSkill
                    )
                    statusMessage?.let { StatusBanner(it) }
                    if (messages.isEmpty()) EmptyConversation(Modifier.weight(1f)) else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesktopSpacing.small)) {
                            items(messages, key = { it.id }) { ConversationMessage(it) }
                        }
                    }
                    if (stagedAttachments.isNotEmpty()) StagedAttachments(stagedAttachments, onRemoveAttachment)
                    Composer(
                        input = input,
                        inputFocused = inputFocused,
                        focusRequester = composerFocus,
                        onInputChange = { input = it },
                        onFocusChange = { inputFocused = it },
                        onSubmit = ::submitInput,
                        onAddAttachment = onAddAttachment
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    hasMessages: Boolean,
    compact: Boolean,
    showCapabilityHints: Boolean,
    onToggleCapabilityHints: () -> Unit,
    onClear: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("AIRI Desktop", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                if (compact) "Capability-gated desktop runtime" else "Capability-gated desktop runtime · local session persistence",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onToggleCapabilityHints) {
            Text(if (showCapabilityHints) "Hide hints" else "Show hints")
        }
        TextButton(onClick = onClear, enabled = hasMessages) { Text("Clear history") }
    }
}

@Composable
private fun CapabilityBar(
    compact: Boolean,
    showCapabilityHints: Boolean,
    selectedModelName: String?,
    models: List<com.airi.core.models.ModelDescriptor>,
    modelMenuExpanded: Boolean,
    onModelMenuExpandedChange: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    selectedSkillName: String?,
    skills: List<com.airi.core.skills.SkillDescriptor>,
    skillMenuExpanded: Boolean,
    onSkillMenuExpandedChange: (Boolean) -> Unit,
    onSelectSkill: (String) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSpacing.small), verticalArrangement = Arrangement.spacedBy(DesktopSpacing.xSmall)) {
        CapabilityMenu("Model: ${selectedModelName ?: "Not configured"}", modelMenuExpanded, onModelMenuExpandedChange) {
            models.forEach { model ->
                DropdownMenuItem(
                    text = { CapabilityMenuText(model.displayName, model.unavailableReason) },
                    enabled = model.availability == ModelAvailability.READY,
                    onClick = { onSelectModel(model.id); onModelMenuExpandedChange(false) }
                )
            }
        }
        CapabilityMenu("Skill: ${selectedSkillName ?: "No ready skill"}", skillMenuExpanded, onSkillMenuExpandedChange) {
            skills.forEach { skill ->
                DropdownMenuItem(
                    text = { CapabilityMenuText(skill.displayName, skill.unavailableReason) },
                    enabled = skill.availability == SkillAvailability.READY,
                    onClick = { onSelectSkill(skill.id); onSkillMenuExpandedChange(false) }
                )
            }
        }
                        if (!compact && showCapabilityHints) Text("Ctrl+N new draft · Ctrl+K focus input · Esc dismiss", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = DesktopSpacing.small))
    }
}

@Composable
private fun CapabilityMenu(label: String, expanded: Boolean, onExpandedChange: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column {
        TextButton(onClick = { onExpandedChange(true) }, modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outline, DesktopShapes.small)) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, content = content)
    }
}

@Composable
private fun CapabilityMenuText(name: String, reason: String?) {
    Column { Text(name); reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
}

@Composable
private fun StatusBanner(message: String) {
    Surface(color = DesktopColors.warning.copy(alpha = 0.12f), shape = DesktopShapes.medium, modifier = Modifier.fillMaxWidth().border(1.dp, DesktopColors.warning.copy(alpha = 0.45f), DesktopShapes.medium)) {
        Text(message, Modifier.padding(DesktopSpacing.medium), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Composer(input: String, inputFocused: Boolean, focusRequester: FocusRequester, onInputChange: (String) -> Unit, onFocusChange: (Boolean) -> Unit, onSubmit: () -> Unit, onAddAttachment: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DesktopSpacing.small)) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocusChange(it.isFocused) },
            label = { Text("Ask AIRI") },
            placeholder = { Text("اكتب طلبك ثم اضغط Enter") },
            supportingText = { Text(if (inputFocused) "Enter للإرسال · Shift+Enter لسطر جديد" else "Ctrl+K للتركيز على حقل الإدخال") },
            minLines = 2,
            maxLines = 5
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onAddAttachment) { Text("Add file") }
            Spacer(Modifier.weight(1f))
            Button(onClick = onSubmit, enabled = input.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun StagedAttachments(attachments: List<DesktopAttachment>, onRemoveAttachment: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSpacing.small), verticalArrangement = Arrangement.spacedBy(DesktopSpacing.xSmall)) {
        attachments.forEach { attachment -> TextButton(onClick = { onRemoveAttachment(attachment.id) }) { Text("${attachment.displayName} · remove") } }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Configure a compatible model to use AIRI on this desktop", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(DesktopSpacing.small))
        Text("Conversation history and attachment staging are local. Model and skill execution remain capability-gated.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConversationMessage(message: DesktopMessage) {
    val isAiri = message.speaker == DesktopSpeaker.AIRI
    Card(Modifier.fillMaxWidth(), shape = if (isAiri) DesktopShapes.airiMessage else DesktopShapes.userMessage, colors = CardDefaults.cardColors(containerColor = if (isAiri) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(DesktopSpacing.large)) {
            Text(if (isAiri) "AIRI" else "You", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(DesktopSpacing.xSmall))
            Text(message.body, style = MaterialTheme.typography.bodyLarge)
            message.attachments.forEach { Text("Attachment: ${it.displayName} (${it.sizeBytes} bytes)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = DesktopSpacing.small)) }
        }
    }
}

private fun chooseDesktopFile(): Path? {
    val dialog = FileDialog(null as Frame?, "Add attachment", FileDialog.LOAD)
    dialog.isVisible = true
    return dialog.directory?.let { directory -> dialog.file?.let { file -> Path.of(directory, file) } }
}
