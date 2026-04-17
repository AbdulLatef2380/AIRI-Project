package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.customskill.CustomSkillSecurity
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillBuilderScreen(
    skillId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { CustomSkillRepository(context) }
    val existing = remember(skillId) { skillId?.let { repository.getSkillById(it) } }
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var endpoint by remember { mutableStateOf(existing?.config?.endpoint.orEmpty()) }
    var method by remember { mutableStateOf(existing?.config?.method ?: "POST") }
    var type by remember { mutableStateOf(existing?.type ?: SkillType.API) }
    var bodyTemplate by remember {
        mutableStateOf(existing?.config?.bodyTemplate ?: """{"message":"{{user_input}}","timestamp":"{{timestamp}}","user_id":"{{user_id}}"}""")
    }
    val headers = remember(existing?.id) {
        mutableStateListOf<HeaderInput>().apply {
            existing?.config?.headers?.forEach { (key, value) -> add(HeaderInput(UUID.randomUUID().toString(), key, value)) }
            if (isEmpty()) add(HeaderInput(UUID.randomUUID().toString(), "", ""))
        }
    }

    fun show(message: String) {
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    fun save() {
        val premiumCheck = PolicyEngine.checkCustomSkillsPremium(ServiceLocator.subscriptionManager)
        if (premiumCheck is PolicyEngine.PolicyResult.Denied) {
            show(premiumCheck.error.message)
            return
        }
        if (name.isBlank()) {
            show("Skill name is required")
            return
        }
        if (!CustomSkillSecurity.isValidEndpoint(endpoint)) {
            show("Endpoint must be a valid URL")
            return
        }
        if (bodyTemplate.isBlank()) {
            show("Body template cannot be empty")
            return
        }
        val skill = CustomSkill(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            description = description.trim(),
            type = type,
            config = SkillConfig(
                endpoint = endpoint.trim(),
                method = method,
                headers = headers.filter { it.key.isNotBlank() }.associate { it.key.trim() to it.value },
                bodyTemplate = bodyTemplate
            ),
            isPremium = true,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        repository.saveSkill(skill)
        if (existing == null) AnalyticsService.skillCreated(skill.name)
        onSaved()
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.72f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text(if (existing == null) "Create Skill" else "Edit Skill", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = ::save) {
                        Text("Save", color = CosmicAccent, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SkillBuilderSection("Details") {
                SkillTextField(name, { name = it }, "Name")
                SkillTextField(description, { description = it }, "Description", minLines = 2)
            }

            SkillBuilderSection("Type") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillType.values().forEach { option ->
                        FilterChip(
                            selected = type == option,
                            onClick = { type = option },
                            label = { Text(option.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CosmicAccent,
                                labelColor = Color.White.copy(alpha = 0.72f)
                            )
                        )
                    }
                }
            }

            SkillBuilderSection("Request") {
                SkillTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    label = "Endpoint URL",
                    keyboardType = KeyboardType.Uri
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { option ->
                        FilterChip(
                            selected = method == option,
                            onClick = { method = option },
                            label = { Text(option) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CosmicAccent.copy(alpha = 0.2f),
                                selectedLabelColor = CosmicAccent,
                                labelColor = Color.White.copy(alpha = 0.72f)
                            )
                        )
                    }
                }
            }

            SkillBuilderSection("Headers") {
                HeaderEditor(headers)
            }

            SkillBuilderSection("Body Template") {
                SkillTextField(
                    value = bodyTemplate,
                    onValueChange = { bodyTemplate = it },
                    label = "JSON body template",
                    minLines = 8
                )
                Text("Supported variables: {{user_input}}, {{timestamp}}, {{user_id}}", color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp)
            }

            Button(
                onClick = ::save,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
            ) {
                Text("Save Skill", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SkillBuilderSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        content()
    }
}

@Composable
private fun SkillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        minLines = minLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CosmicAccent,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = CosmicAccent,
            unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
            cursorColor = CosmicAccent
        )
    )
}

@Composable
private fun HeaderEditor(headers: SnapshotStateList<HeaderInput>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        headers.forEachIndexed { index, header ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = header.key,
                    onValueChange = { headers[index] = header.copy(key = it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Key") },
                    singleLine = true,
                    colors = headerFieldColors()
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { headers[index] = header.copy(value = it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("Value") },
                    singleLine = true,
                    colors = headerFieldColors()
                )
                IconButton(
                    onClick = {
                        if (headers.size > 1) headers.removeAt(index) else headers[index] = HeaderInput(header.id, "", "")
                    }
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Remove header", tint = Color(0xFFFF6B6B))
                }
            }
        }
        TextButton(onClick = { headers.add(HeaderInput(UUID.randomUUID().toString(), "", "")) }) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add header", color = CosmicAccent)
        }
    }
}

@Composable
private fun headerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CosmicAccent,
    unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = CosmicAccent,
    unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
    cursorColor = CosmicAccent
)

private data class HeaderInput(
    val id: String,
    val key: String,
    val value: String
)