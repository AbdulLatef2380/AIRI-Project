package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.LibraryBooks
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
import com.airi.assistant.ui.components.NeuralBadge
import com.airi.assistant.ui.components.NeuralSearchBar
import com.airi.assistant.ui.components.NeuralScreenHeader
import com.airi.assistant.ui.theme.*

@Composable
fun SkillManagerScreen(
    onBack:           () -> Unit,
    onCreate:         () -> Unit,
    onEdit:           (String) -> Unit,
    onBrowseTemplates: () -> Unit = {}
) {
    val context    = LocalContext.current
    val repository = remember { CustomSkillRepository(context) }
    var skills     by remember { mutableStateOf(repository.getAllSkills()) }
    var query      by remember { mutableStateOf("") }

    fun reload() { skills = repository.getAllSkills() }

    val filtered = remember(skills, query) {
        if (query.isBlank()) skills
        else skills.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true) ||
            it.type.name.contains(query, ignoreCase = true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            NeuralScreenHeader(
                title          = "المهارات المخصصة",
                onBack         = onBack,
                trailingContent = {
                    Row {
                        IconButton(onClick = onBrowseTemplates) {
                            Icon(Icons.Outlined.LibraryBooks, contentDescription = "قوالب", tint = PrimaryAccent)
                        }
                        IconButton(onClick = onCreate) {
                            Icon(Icons.Filled.Add, contentDescription = "إضافة", tint = PrimaryAccent)
                        }
                    }
                }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                NeuralSearchBar(
                    query         = query,
                    onQueryChange = { query = it },
                    placeholder   = "بحث في المهارات..."
                )

                Spacer(Modifier.height(16.dp))

                if (filtered.isEmpty() && skills.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(PrimaryAccent.copy(alpha = 0.12f))
                                    .border(1.dp, PrimaryAccent.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Outlined.Extension,
                                    contentDescription = null,
                                    tint     = PrimaryAccent,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                            Text(
                                "لا توجد مهارات بعد",
                                color      = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize   = 18.sp
                            )
                            Text(
                                "أنشئ مهارة API أو Webhook ليستخدمها AIRI أداةً.",
                                color    = TextSecondary,
                                fontSize = 13.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(PrimaryAccent)
                                    .clickable(onClick = onCreate)
                                    .padding(horizontal = 22.dp, vertical = 11.dp)
                            ) {
                                Text("إنشاء مهارة", color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("لا توجد نتائج لـ \"$query\"", color = TextTertiary, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 96.dp)
                    ) {
                        items(filtered, key = { it.id }) { skill ->
                            SkillCard(
                                skill    = skill,
                                onClick  = { onEdit(skill.id) },
                                onDelete = { repository.deleteSkill(skill.id); reload() }
                            )
                        }
                    }
                }
            }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PrimaryAccent)
                .clickable(onClick = onCreate),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "إنشاء مهارة", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
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
            .background(Surface2)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Icon badge
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(PrimaryAccent.copy(alpha = 0.12f))
                .border(1.dp, PrimaryAccent.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Extension,
                contentDescription = null,
                tint     = PrimaryAccent,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    skill.name,
                    color      = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 15.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                NeuralBadge(text = skill.type.name, color = PrimaryAccent)
            }
            Text(
                skill.description,
                color    = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                skill.config.endpoint,
                color    = TextTertiary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "حذف", tint = SemanticError.copy(alpha = 0.80f))
        }
    }
}
