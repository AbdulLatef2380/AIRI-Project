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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.customskill.SkillConfig
import com.airi.assistant.domain.customskill.SkillType
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.CosmicBlack
import com.airi.assistant.ui.theme.SemanticError
import com.airi.assistant.ui.theme.SurfaceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.util.UUID

// ── Import source options ─────────────────────────────────────────────────────
private enum class ImportSource { STORAGE, GITHUB, AI }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(
    onBack:   () -> Unit,
    onCreate: () -> Unit,
    onEdit:   (String) -> Unit
) {
    val context    = LocalContext.current
    val repository = remember { CustomSkillRepository(context) }
    val scope      = rememberCoroutineScope()

    var skills          by remember { mutableStateOf(repository.getAllSkills()) }
    var showAddMenu     by remember { mutableStateOf(false) }
    var importSource    by remember { mutableStateOf<ImportSource?>(null) }
    var errorMessage    by remember { mutableStateOf<String?>(null) }
    var isImporting     by remember { mutableStateOf(false) }

    fun reload() { skills = repository.getAllSkills() }

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
                    errorMessage = "فشل استيراد المهارة: ${e.message}"
                }
            }
        }
    }

    Scaffold(
        containerColor = CosmicBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CosmicBlack.copy(alpha = 0.95f)
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = {
                    Column {
                        Text("المهارات", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (skills.isNotEmpty()) {
                            Text(
                                "${skills.size} مهارة",
                                color = Color.White.copy(0.45f), fontSize = 11.sp
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showAddMenu = true }) {
                            Icon(Icons.Default.Add, contentDescription = "Add skill", tint = CosmicAccent)
                        }
                        DropdownMenu(
                            expanded         = showAddMenu,
                            onDismissRequest = { showAddMenu = false }
                        ) {
                            AddOption(Icons.Outlined.Edit, "إنشاء مهارة جديدة") {
                                showAddMenu = false; onCreate()
                            }
                            AddOption(Icons.Outlined.FolderOpen, "استيراد من التخزين") {
                                showAddMenu = false
                                importSource = ImportSource.STORAGE
                                filePicker.launch("application/json")
                            }
                            AddOption(Icons.Outlined.Code, "استيراد من GitHub") {
                                showAddMenu = false; importSource = ImportSource.GITHUB
                            }
                            AddOption(Icons.Outlined.AutoAwesome, "إنشاء مع AIRI") {
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

            // ── GitHub import dialog ───────────────────────────────────────────
            if (importSource == ImportSource.GITHUB) {
                GitHubImportDialog(
                    isImporting = isImporting,
                    onDismiss   = { importSource = null },
                    onImport    = { rawUrl ->
                        scope.launch(Dispatchers.IO) {
                            withContext(Dispatchers.Main) { isImporting = true }
                            runCatching {
                                val json = URL(rawUrl).readText()
                                val skill = parseSkillJson(json)
                                repository.saveSkill(skill)
                                withContext(Dispatchers.Main) {
                                    reload(); importSource = null; isImporting = false
                                }
                            }.onFailure { e ->
                                withContext(Dispatchers.Main) {
                                    isImporting = false
                                    errorMessage = "فشل استيراد GitHub: ${e.message}"
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

            // ── Skill list ─────────────────────────────────────────────────────
            if (skills.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            "لا توجد مهارات مخصصة بعد",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            "أنشئ مهارات API أو استوردها، ويمكن لـ AIRI استخدامها كأدوات.",
                            color = Color.White.copy(0.55f),
                            fontSize = 14.sp
                        )
                        Button(
                            onClick = onCreate,
                            colors  = ButtonDefaults.buttonColors(
                                containerColor = CosmicAccent,
                                contentColor   = Color.White
                            )
                        ) {
                            Text("إنشاء مهارة", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    items(skills, key = { it.id }) { skill ->
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
        title = { Text("استيراد من GitHub", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "الصق رابط الملف الخام (raw) لملف JSON الخاص بالمهارة من GitHub.",
                    color = Color.White.copy(0.6f), fontSize = 13.sp
                )
                OutlinedTextField(
                    value         = rawUrl,
                    onValueChange = { rawUrl = it },
                    placeholder   = {
                        Text(
                            "https://raw.githubusercontent.com/…/skill.json",
                            color = Color.White.copy(0.3f), fontSize = 11.sp
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
                    color = CosmicAccent
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick  = { if (rawUrl.isNotBlank()) onImport(rawUrl.trim()) },
                enabled  = rawUrl.isNotBlank() && !isImporting
            ) {
                Text("استيراد", color = CosmicAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.White.copy(0.55f)) }
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
        containerColor   = Color(0xFF111525),
        title = {
            Text("إنشاء مهارة مع AIRI", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "أدخل تفاصيل المهارة وسيقوم AIRI بإعداد الهيكل الكامل لها.",
                    color = Color.White.copy(0.6f), fontSize = 13.sp
                )
                SkillTextField("اسم المهارة", name) { name = it }
                SkillTextField("وصف المهارة", description) { description = it }
                SkillTextField("عنوان API (URL)", endpoint, keyboard = KeyboardType.Uri) { endpoint = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick  = {
                    if (name.isNotBlank() && endpoint.isNotBlank())
                        onCreate(name.trim(), description.trim(), endpoint.trim())
                },
                enabled = name.isNotBlank() && endpoint.isNotBlank()
            ) {
                Text("إنشاء", color = CosmicAccent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء", color = Color.White.copy(0.55f)) }
        }
    )
}

@Composable
private fun SkillTextField(
    label: String,
    value: String,
    keyboard: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        label         = { Text(label, fontSize = 12.sp, color = Color.White.copy(0.5f)) },
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
    val obj  = JSONObject(json)
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
            .background(SurfaceCard)
            .border(1.dp, Color.White.copy(0.07f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    skill.name,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = CosmicAccent.copy(alpha = 0.14f)
                ) {
                    Text(
                        skill.type.name,
                        color = CosmicAccent,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                skill.description,
                color = Color.White.copy(0.55f),
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                skill.config.endpoint,
                color = Color.White.copy(0.3f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "Delete",
                tint = Color(0xFFFF6B6B)
            )
        }
    }
}
