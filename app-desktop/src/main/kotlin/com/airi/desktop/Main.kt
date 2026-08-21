package com.airi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.airi.core.models.ModelAvailability
import com.airi.core.skills.SkillAvailability
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

fun main() = application {
    val agent = remember { DesktopAgent() }
    var messages by remember { mutableStateOf(agent.history()) }
    var stagedAttachments by remember { mutableStateOf(agent.stagedAttachments()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "AIRI Desktop"
    ) {
        AiriDesktopApp(
            messages = messages,
            stagedAttachments = stagedAttachments,
            selectedModelName = agent.selectedModel()?.displayName,
            models = agent.availableModels(),
            selectedSkillName = agent.selectedSkill()?.displayName,
            skills = agent.availableSkills(),
            statusMessage = statusMessage,
            onSubmit = { input ->
                val reply = agent.submit(input)
                if (reply != null) {
                    messages = agent.history()
                    stagedAttachments = agent.stagedAttachments()
                    statusMessage = reply.message.body
                }
            },
            onSelectModel = { modelId ->
                val result = agent.selectModel(modelId)
                statusMessage = when (result) {
                    is com.airi.core.models.ModelSelectionResult.Selected -> "Selected ${result.model.displayName}."
                    is com.airi.core.models.ModelSelectionResult.Rejected -> result.reason
                }
            },
            onSelectSkill = { skillId ->
                val result = agent.selectSkill(skillId)
                statusMessage = when (result) {
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

    fun submitInput() {
        if (input.isBlank()) return
        onSubmit(input)
        input = ""
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7DD3FC),
            secondary = Color(0xFF67E8F9),
            surface = Color(0xFF0F172A),
            background = Color(0xFF020617)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AIRI Desktop",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Desktop runtime · capability-gated · local persistence",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClear, enabled = messages.isNotEmpty()) {
                        Text("Clear local history")
                    }
                }

                CapabilityBar(
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

                statusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (messages.isEmpty()) {
                    EmptyConversation(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            ConversationMessage(message)
                        }
                    }
                }

                if (stagedAttachments.isNotEmpty()) {
                    StagedAttachments(stagedAttachments, onRemoveAttachment)
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed
                            ) {
                                submitInput()
                                true
                            } else {
                                false
                            }
                        },
                    label = { Text("Ask AIRI") },
                    placeholder = { Text("اكتب طلبك ثم اضغط Enter") },
                    minLines = 2,
                    maxLines = 5
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onAddAttachment) {
                        Text("Add file")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Enter للإرسال · Shift+Enter لسطر جديد",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(onClick = ::submitInput, enabled = input.isNotBlank()) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityBar(
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
    Row(modifier = Modifier.fillMaxWidth()) {
        Column {
            TextButton(onClick = { onModelMenuExpandedChange(true) }) {
                Text("Model: ${selectedModelName ?: "Not configured"}")
            }
            DropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { onModelMenuExpandedChange(false) }
            ) {
                models.forEach { model ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(model.displayName)
                                model.unavailableReason?.let { reason ->
                                    Text(reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        enabled = model.availability == ModelAvailability.READY,
                        onClick = {
                            onSelectModel(model.id)
                            onModelMenuExpandedChange(false)
                        }
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            TextButton(onClick = { onSkillMenuExpandedChange(true) }) {
                Text("Skill: ${selectedSkillName ?: "No ready skill"}")
            }
            DropdownMenu(
                expanded = skillMenuExpanded,
                onDismissRequest = { onSkillMenuExpandedChange(false) }
            ) {
                skills.forEach { skill ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(skill.displayName)
                                skill.unavailableReason?.let { reason ->
                                    Text(reason, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        enabled = skill.availability == SkillAvailability.READY,
                        onClick = {
                            onSelectSkill(skill.id)
                            onSkillMenuExpandedChange(false)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StagedAttachments(
    attachments: List<DesktopAttachment>,
    onRemoveAttachment: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { attachment ->
            TextButton(onClick = { onRemoveAttachment(attachment.id) }) {
                Text("${attachment.displayName} · remove")
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Configure a compatible model to use AIRI on this desktop",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Conversation history and attachment staging are available locally. Model and skill execution remain capability-gated.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConversationMessage(message: DesktopMessage) {
    val isAiri = message.speaker == DesktopSpeaker.AIRI
    val containerColor = if (isAiri) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isAiri) "AIRI" else "You",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
            message.attachments.forEach { attachment ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Attachment: ${attachment.displayName} (${attachment.sizeBytes} bytes)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun chooseDesktopFile(): Path? {
    val dialog = FileDialog(null as Frame?, "Add attachment", FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return Path.of(directory, file)
}
