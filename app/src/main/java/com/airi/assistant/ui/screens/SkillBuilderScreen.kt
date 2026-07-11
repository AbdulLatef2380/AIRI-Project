package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.customskill.CustomSkillSecurity
import com.airi.assistant.domain.customskill.SkillCircuitBreaker
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.AiriTheme
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
private data class SkillPreset(
    val label: String,
    val name: String,
    val description: String,
    val type: SkillType,
    val method: String,
    val endpoint: String,
    val headers: List<HeaderInput>,
    val bodyTemplate: String
)

private val SKILL_PRESETS = listOf(
    SkillPreset(
        label = "Send Message API",
        name = "Send Message",
        description = "Sends a message to an external messaging API",
        type = SkillType.API,
        method = "POST",
        endpoint = "https://api.example.com/messages",
        headers = listOf(
            HeaderInput(UUID.randomUUID().toString(), "Authorization", "Bearer YOUR_API_KEY"),
            HeaderInput(UUID.randomUUID().toString(), "Content-Type", "application/json")
        ),
        bodyTemplate = """{"message":"{{user_input}}","timestamp":"{{timestamp}}","user_id":"{{user_id}}"}"""
    ),
    SkillPreset(
        label = "Webhook Trigger",
        name = "Webhook Trigger",
        description = "Triggers a webhook with user input and context",
        type = SkillType.WEBHOOK,
        method = "POST",
        endpoint = "https://hooks.example.com/trigger",
        headers = listOf(
            HeaderInput(UUID.randomUUID().toString(), "X-Api-Key", "YOUR_SECRET_KEY")
        ),
        bodyTemplate = """{"event":"airi_trigger","payload":{"input":"{{user_input}}","context":"{{conversation_context}}","goal":"{{agent_goal}}","ts":"{{timestamp}}"}}"""
    ),
    SkillPreset(
        label = "Basic POST Request",
        name = "Custom API Call",
        description = "Makes a basic POST request to an API endpoint",
        type = SkillType.API,
        method = "POST",
        endpoint = "https://api.example.com/endpoint",
        headers = listOf(
            HeaderInput(UUID.randomUUID().toString(), "Content-Type", "application/json")
        ),
        bodyTemplate = """{"query":"{{user_input}}","user_id":"{{user_id}}"}"""
    )
)
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
        mutableStateOf(
            existing?.config?.bodyTemplate
                ?: """{"message":"{{user_input}}","timestamp":"{{timestamp}}","user_id":"{{user_id}}"}"""
        )
    }
    val headers = remember(existing?.id) {
        mutableStateListOf<HeaderInput>().apply {
            existing?.config?.headers?.forEach { (key, value) ->
                add(HeaderInput(UUID.randomUUID().toString(), key, value))
            }
            if (isEmpty()) add(HeaderInput(UUID.randomUUID().toString(), "", ""))
        }
    }

    var showTemplates by remember { mutableStateOf(existing == null) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val circuitHealth = remember(skillId) {
        existing?.id?.let { SkillCircuitBreaker.getHealthState(it) }
            ?: SkillCircuitBreaker.SkillHealth.HEALTHY
    }

    fun show(message: String) {
        scope.launch { snackbarHost.showSnackbar(message) }
    }

    fun applyPreset(preset: SkillPreset) {
        name = preset.name
        description = preset.description
        endpoint = preset.endpoint
        method = preset.method
        type = preset.type
        bodyTemplate = preset.bodyTemplate
        headers.clear()
        headers.addAll(preset.headers.map { it.copy(id = UUID.randomUUID().toString()) })
        if (headers.isEmpty()) headers.add(HeaderInput(UUID.randomUUID().toString(), "", ""))
        showTemplates = false
        show("Template applied — update the endpoint and credentials before saving")
    }

    fun buildSkill(): CustomSkill? {
        if (name.isBlank()) { show("Skill name is required"); return null }
        if (!CustomSkillSecurity.isValidEndpoint(endpoint)) {
            show("Endpoint must be a valid URL starting with https:// or http://")
            return null
        }
        if (bodyTemplate.isBlank()) { show("Body template cannot be empty"); return null }
        return CustomSkill(
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
    }

    fun save() {
        if (isSaving) return
        val premiumCheck = PolicyEngine.checkCustomSkillsPremium(ServiceLocator.subscriptionManager)
        if (premiumCheck is PolicyEngine.PolicyResult.Denied) {
            show(premiumCheck.error.message)
            return
        }
        val skill = buildSkill() ?: return
        isSaving = true
        scope.launch {
            try {
                repository.saveSkill(skill)
                if (existing == null) AnalyticsService.skillCreated(skill.name)
                snackbarHost.showSnackbar(
                    message = if (existing == null) "Skill created successfully" else "Skill updated successfully",
                    duration = SnackbarDuration.Short
                )
                onSaved()
            } catch (e: Exception) {
                show("Failed to save skill: ${e.message ?: "Unknown error"}")
            } finally {
                isSaving = false
            }
        }
    }

    fun testSkill() {
        if (isTesting) return
        val skill = buildSkill() ?: return
        isTesting = true
        scope.launch {
            try {
                val executor = CustomSkillExecutor(context)
                val result = executor.execute(
                    skill,
                    mapOf(
                        "user_input" to "test",
                        "conversation_context" to "This is a test execution",
                        "agent_goal" to "verify skill connectivity"
                    )
                )
                testResult = TestResult(
                    success = result.success,
                    rawOutput = if (result.success) result.data else "",
                    errorMessage = if (!result.success) (result.error ?: "Unknown error") else null
                )
            } catch (e: Exception) {
                testResult = TestResult(
                    success = false,
                    rawOutput = "",
                    errorMessage = e.message ?: "Unexpected error during test"
                )
            } finally {
                isTesting = false
            }
        }
    }

    testResult?.let { result ->
        TestResultDialog(result = result, onDismiss = { testResult = null })
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black.copy(alpha = 0.72f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(
                        if (existing == null) "Create Skill" else "Edit Skill",
                        color = AiriTheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp).padding(end = 16.dp),
                            color = CosmicAccent,
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = ::save, enabled = !isSaving) {
                            Text(stringResource(R.string.save), color = CosmicAccent, fontWeight = FontWeight.Bold)
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (circuitHealth == SkillCircuitBreaker.SkillHealth.OPEN) {
                SkillHealthBanner(
                    message = "This skill is temporarily disabled — it failed too many times. It will auto-recover after 60s.",
                    color = Color(0xFFFF6B6B)
                )
            } else if (circuitHealth == SkillCircuitBreaker.SkillHealth.DEGRADED) {
                SkillHealthBanner(
                    message = "This skill has recent failures. It may be unstable.",
                    color = Color(0xFFFFA726)
                )
            }
            SkillBuilderSection(
                title = "Quick Templates",
                collapsible = true,
                expanded = showTemplates,
                onToggle = { showTemplates = !showTemplates }
            ) {
                Text(
                    "Start from a pre-built template to save time. You can edit everything after applying.",
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(4.dp))
                SKILL_PRESETS.forEach { preset ->
                    PresetCard(preset = preset, onClick = { applyPreset(preset) })
                }
            }
            SkillBuilderSection("Details") {
                SkillTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Skill Name",
                    helperText = "A short, descriptive name the AI will use to identify this skill (e.g. 'Send Slack Message')"
                )
                SkillTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Description",
                    minLines = 2,
                    helperText = "Explain what this skill does. The AI uses this description to decide when to call it."
                )
            }
            // SkillType.LOCAL is intentionally hidden from the chip row.
            // CustomSkillExecutor.execute() rejects LOCAL skills with the message
            // "Local custom skills are not executable yet." — exposing the chip
            // let users save a skill that would always fail at run-time. The
            // enum value remains in source so any pre-existing LOCAL skills
            // still load (they will surface the executor's rejection message
            // when triggered) and so the type is available for future on-device
            // skills without a data migration.
            SkillBuilderSection("Skill Type") {
                Text(
                    "API: Calls an external REST API. Webhook: Triggers an event endpoint.",
                    color = AiriTheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 12.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkillType.values()
                        .filter { it != SkillType.LOCAL }
                        .forEach { option ->
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
                    keyboardType = KeyboardType.Uri,
                    helperText = "The full URL this skill will call (e.g. https://api.example.com/action). Must start with https:// or http://."
                )
                Text(
                    "HTTP Method — choose how the request is sent:",
                    color = AiriTheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 12.sp
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
                if (method in listOf("GET", "HEAD")) {
                    Text(
                        "GET/HEAD methods use READ_ONLY permission — POST/PUT/DELETE body fields will be ignored.",
                        color = Color(0xFFFFA726).copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }
            }
            SkillBuilderSection("Headers") {
                Text(
                    "Add HTTP headers such as Authorization or API keys. Sensitive values (API keys, tokens) are encrypted before storage.",
                    color = AiriTheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 12.sp
                )
                HeaderEditor(headers)
            }
            SkillBuilderSection("Body Template") {
                Text(
                    "Write the JSON body to send with the request. Use template variables to inject dynamic values at runtime.",
                    color = AiriTheme.onBackground.copy(alpha = 0.45f),
                    fontSize = 12.sp
                )
                SkillTextField(
                    value = bodyTemplate,
                    onValueChange = { bodyTemplate = it },
                    label = "JSON body template",
                    minLines = 8
                )
                TemplateVariablesHint()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = ::testSkill,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isTesting && !isSaving,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CosmicAccent),
                    border = BorderStroke(1.dp, CosmicAccent.copy(alpha = if (isTesting) 0.3f else 0.6f))
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CosmicAccent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.testing_label), fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.test_skill), fontWeight = FontWeight.SemiBold)
                    }
                }

                Button(
                    onClick = ::save,
                    modifier = Modifier.weight(1f).height(54.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !isSaving && !isTesting,
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.Black, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.skill_builder_saving), fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.skill_builder_save_skill), fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
@Composable
private fun SkillHealthBanner(message: String, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(message, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
private data class TestResult(
    val success: Boolean,
    val rawOutput: String,
    val errorMessage: String?
)

@Composable
private fun TestResultDialog(result: TestResult, onDismiss: () -> Unit) {
    val statusColor = if (result.success) Color(0xFF3DDC97) else Color(0xFFFF6B6B)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(999.dp), color = statusColor.copy(alpha = 0.15f)) {
                    Text(
                        text = if (result.success) "Connected" else "Failed",
                        color = statusColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = if (result.success) "Skill responded successfully" else "Skill did not respond",
                    color = AiriTheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            }

            if (result.success && result.rawOutput.isNotBlank()) {
                val formatted = remember(result.rawOutput) { formatSkillOutput(result.rawOutput) }
                Text(stringResource(R.string.skill_builder_response), color = AiriTheme.onBackground.copy(alpha = 0.45f), fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Text(
                        text = formatted,
                        color = Color(0xFF3DDC97).copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            if (!result.errorMessage.isNullOrBlank()) {
                Text(stringResource(R.string.skill_builder_error), color = AiriTheme.onBackground.copy(alpha = 0.45f), fontSize = 11.sp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFFF6B6B).copy(alpha = 0.08f))
                        .border(1.dp, Color(0xFFFF6B6B).copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = result.errorMessage.take(600),
                        color = Color(0xFFFF6B6B).copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    "Fix the error above, then test again before saving.",
                    color = AiriTheme.onBackground.copy(alpha = 0.38f),
                    fontSize = 11.sp
                )
            }

            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dismiss), color = CosmicAccent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun formatSkillOutput(rawJson: String): String {
    return runCatching {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var escape = false
        for (ch in rawJson) {
            when {
                escape -> { sb.append(ch); escape = false }
                ch == '\\' && inString -> { sb.append(ch); escape = true }
                ch == '"' -> { sb.append(ch); inString = !inString }
                inString -> sb.append(ch)
                ch == '{' || ch == '[' -> { sb.append(ch).append('\n'); indent += 2; repeat(indent) { sb.append(' ') } }
                ch == '}' || ch == ']' -> { sb.append('\n'); indent = (indent - 2).coerceAtLeast(0); repeat(indent) { sb.append(' ') }; sb.append(ch) }
                ch == ',' -> { sb.append(ch).append('\n'); repeat(indent) { sb.append(' ') } }
                ch == ':' -> sb.append(": ")
                ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t' -> sb.append(ch)
                ch == ' ' || ch == '\n' -> Unit
                else -> sb.append(ch)
            }
        }
        sb.toString().take(1200)
    }.getOrElse { rawJson.take(1200) }
}
@Composable
private fun TemplateVariablesHint() {
    val variables = listOf(
        "{{user_input}}" to "The user's current message or request",
        "{{timestamp}}" to "Current Unix timestamp in milliseconds",
        "{{user_id}}" to "Unique ID of the authenticated user",
        "{{conversation_context}}" to "Recent conversation history for context",
        "{{agent_goal}}" to "The agent's current objective or intent"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(CosmicAccent.copy(alpha = 0.05f))
            .border(1.dp, CosmicAccent.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.skill_builder_template_vars), color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        variables.forEach { (variable, explanation) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    variable,
                    color = CosmicAccent.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.widthIn(min = 160.dp)
                )
                Text("— $explanation", color = AiriTheme.onBackground.copy(alpha = 0.45f), fontSize = 11.sp)
            }
        }
    }
}
@Composable
private fun PresetCard(preset: SkillPreset, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CosmicAccent.copy(alpha = 0.07f))
            .border(1.dp, CosmicAccent.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(preset.label, color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(preset.description, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Surface(shape = RoundedCornerShape(999.dp), color = CosmicAccent.copy(alpha = 0.15f)) {
            Text(
                "Use",
                color = CosmicAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

// ─── Section Container ─────────────────────────────────────────────────────���──

@Composable
private fun SkillBuilderSection(
    title: String,
    collapsible: Boolean = false,
    expanded: Boolean = true,
    onToggle: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (collapsible && onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            if (collapsible && onToggle != null) {
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = CosmicAccent.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        AnimatedVisibility(
            visible = if (collapsible) expanded else true,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content
            )
        }
    }
}
@Composable
private fun SkillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    helperText: String? = null
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
        if (!helperText.isNullOrBlank()) {
            Text(
                text = helperText,
                color = AiriTheme.onBackground.copy(alpha = 0.38f),
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
@Composable
private fun HeaderEditor(headers: SnapshotStateList<HeaderInput>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        headers.forEachIndexed { index, header ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = header.key,
                    onValueChange = { headers[index] = header.copy(key = it) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.skill_builder_header_key)) },
                    singleLine = true,
                    colors = headerFieldColors()
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { headers[index] = header.copy(value = it) },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.skill_builder_header_value)) },
                    singleLine = true,
                    colors = headerFieldColors()
                )
                IconButton(
                    onClick = {
                        if (headers.size > 1) headers.removeAt(index)
                        else headers[index] = HeaderInput(header.id, "", "")
                    }
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.skill_builder_remove_header_cd), tint = Color(0xFFFF6B6B))
                }
            }
        }
        TextButton(onClick = { headers.add(HeaderInput(UUID.randomUUID().toString(), "", "")) }) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.skill_builder_add_header), color = CosmicAccent)
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
