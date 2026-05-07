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
import androidx.compose.material.icons.filled.ArrowBack
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
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillManagerScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (String) -> Unit,
    onBrowseTemplates: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { CustomSkillRepository(context) }
    var skills by remember { mutableStateOf(repository.getAllSkills()) }

    fun reload() {
        skills = repository.getAllSkills()
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface1),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                title = { Text("Custom Skills", color = Color.White, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onBrowseTemplates) {
                        Icon(Icons.Outlined.LibraryBooks, contentDescription = "Browse templates", tint = CosmicAccent)
                    }
                    IconButton(onClick = onCreate) {
                        Icon(Icons.Default.Add, contentDescription = "Create skill", tint = CosmicAccent)
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreate,
                containerColor = CosmicAccent,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create skill")
            }
        }
    ) { padding ->
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(48.dp))
                    Text("No custom skills yet", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Create API or webhook skills and AIRI can use them as tools.", color = Color.White.copy(alpha = 0.55f))
                    Button(onClick = onCreate, colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)) {
                        Text("Create Skill", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(skills, key = { it.id }) { skill ->
                    SkillCard(
                        skill = skill,
                        onClick = { onEdit(skill.id) },
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

@Composable
private fun SkillCard(
    skill: CustomSkill,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(999.dp), color = CosmicAccent.copy(alpha = 0.14f)) {
                    Text(skill.type.name, color = CosmicAccent, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Text(skill.description, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(skill.config.endpoint, color = Color.White.copy(alpha = 0.32f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = Color(0xFFFF6B6B))
        }
    }
}