package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*

@Composable
fun SkillManagerScreen(
    onBack:            () -> Unit,
    onCreate:          () -> Unit,
    onEdit:            (String) -> Unit,
    onBrowseTemplates: () -> Unit
) {
    val context = LocalContext.current
    val repo    = remember { CustomSkillRepository(context) }
    var skills  by remember { mutableStateOf(repo.getAllSkills()) }
    var query   by remember { mutableStateOf("") }
    var showCreateSheet by remember { mutableStateOf(false) }

    val filtered = remember(skills, query) {
        if (query.isBlank()) skills
        else skills.filter { it.name.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "المهارات", onBack = onBack) {
                IconButton(onClick = { showCreateSheet = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Skill", tint = PrimaryAccent)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            // Search
            NeuralSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "بحث في المهارات...",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )

            if (filtered.isEmpty() && query.isBlank()) {
                EmptySkillsPlaceholder(
                    onCreate = { showCreateSheet = true },
                    onBrowse = onBrowseTemplates
                )
            } else if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("لا توجد نتائج لـ \"$query\"", color = TextTertiary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { skill ->
                        SkillCard(
                            skill    = skill,
                            onEdit   = { onEdit(skill.id) },
                            onToggle = {
                                val updated = skill.copy(isPremium = !skill.isPremium)
                                repo.saveSkill(updated)
                                skills = repo.getAllSkills()
                            },
                            onDelete = {
                                repo.deleteSkill(skill.id)
                                skills = repo.getAllSkills()
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateSkillSheet(
            onDismiss         = { showCreateSheet = false },
            onBuildWithAiri   = { showCreateSheet = false; onCreate() },
            onUpload          = { showCreateSheet = false; onCreate() },
            onFromLibrary     = { showCreateSheet = false; onBrowseTemplates() },
            onFromGitHub      = { showCreateSheet = false; onCreate() }
        )
    }
}

@Composable
private fun SkillCard(
    skill: CustomSkill,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, if (skill.isPremium) PrimaryAccent.copy(0.25f) else BorderLight, RoundedCornerShape(16.dp))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(PrimaryAccent.copy(0.14f))
                        .border(0.5.dp, PrimaryAccent.copy(0.3f), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(skill.name.take(1).uppercase(), color = PrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(skill.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(skill.description, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                NeuralToggle(checked = skill.isPremium, onCheckedChange = { onToggle() })
            }

            AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Column {
                    NeuralDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextButton(onClick = onEdit, colors = ButtonDefaults.textButtonColors(contentColor = PrimaryAccent)) {
                            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("تعديل", fontSize = 13.sp)
                        }
                        TextButton(onClick = onDelete, colors = ButtonDefaults.textButtonColors(contentColor = SemanticError)) {
                            Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("حذف", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySkillsPlaceholder(onCreate: () -> Unit, onBrowse: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Extension, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(52.dp))
            Text("لا توجد مهارات بعد", color = TextTertiary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("قم بإنشاء مهارة جديدة أو استيراد من المكتبة", color = TextTertiary, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            NeuralAccentButton("إنشاء مهارة", onClick = onCreate, modifier = Modifier.width(220.dp))
            TextButton(onClick = onBrowse, colors = ButtonDefaults.textButtonColors(contentColor = SecondaryAccent)) {
                Text("تصفح المكتبة الرسمية")
            }
        }
    }
}

@Composable
private fun CreateSkillSheet(
    onDismiss: () -> Unit,
    onBuildWithAiri: () -> Unit,
    onUpload: () -> Unit,
    onFromLibrary: () -> Unit,
    onFromGitHub: () -> Unit
) {
    val options = listOf(
        // Using a data class or simple list instead of Triple to avoid destructuring issues if Triple is misused
        listOf(Icons.Outlined.AutoAwesome,  "البناء باستخدام Airi",    "قم ببناء مهارة من خلال المحادثة", PrimaryAccent,    onBuildWithAiri),
        listOf(Icons.Outlined.UploadFile,   "رفع مهارة",               "رفع .skill أو .zip",              SecondaryAccent,  onUpload),
        listOf(Icons.Outlined.LibraryBooks, "من المكتبة الرسمية",      "مهارات جاهزة يصونها Airi",        SemanticSuccess,  onFromLibrary),
        listOf(Icons.Outlined.Code,         "استيراد من GitHub",        "الصق رابط المستودع للبدء",        SemanticWarning,  onFromGitHub)
    )

    NeuralBottomSheet(onDismiss = onDismiss, title = "إنشاء مهارة") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { item ->
                val icon = item[0] as androidx.compose.ui.graphics.vector.ImageVector
                val title = item[1] as String
                val sub = item[2] as String
                val color = item[3] as androidx.compose.ui.graphics.Color
                val action = item[4] as () -> Unit

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(color.copy(alpha = 0.07f))
                        .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .clickable { action() }
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(
                            modifier = Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                        }
                        Column {
                            Text(title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(sub, color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
