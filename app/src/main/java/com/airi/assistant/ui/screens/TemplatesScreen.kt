package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Note
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.BorderLight
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.PrimaryAccent
import com.airi.assistant.ui.theme.SecondaryAccent
import com.airi.assistant.ui.theme.SemanticSuccess
import com.airi.assistant.ui.theme.SemanticWarning
import com.airi.assistant.ui.theme.Surface0
import com.airi.assistant.ui.theme.Surface1
import com.airi.assistant.ui.theme.Surface2
import com.airi.assistant.ui.viewmodel.ChatViewModel

private data class SkillTemplate(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val category: String,
    val tags: List<String>,
    val accentColor: Color
)

private val TEMPLATES = listOf(
    SkillTemplate(
        id          = "web_search",
        name        = "Web Search",
        description = "Let AIRI query any search engine or REST API and summarise results in real time.",
        icon        = Icons.Outlined.Search,
        category    = "Research",
        tags        = listOf("API", "GET", "JSON"),
        accentColor = SecondaryAccent
    ),
    SkillTemplate(
        id          = "email_draft",
        name        = "Email Drafter",
        description = "Compose and optionally send emails via a webhook or SMTP relay endpoint.",
        icon        = Icons.Outlined.Email,
        category    = "Communication",
        tags        = listOf("Webhook", "POST"),
        accentColor = PrimaryAccent
    ),
    SkillTemplate(
        id          = "calendar_query",
        name        = "Calendar Query",
        description = "Read or write calendar events by calling a Google Calendar or CalDAV API.",
        icon        = Icons.Outlined.CalendarMonth,
        category    = "Productivity",
        tags        = listOf("API", "GET", "POST"),
        accentColor = SemanticSuccess
    ),
    SkillTemplate(
        id          = "code_explainer",
        name        = "Code Explainer",
        description = "Send a code snippet to a remote analysis API and receive a plain-English explanation.",
        icon        = Icons.Outlined.Code,
        category    = "Developer",
        tags        = listOf("API", "POST", "JSON"),
        accentColor = CosmicAccent
    ),
    SkillTemplate(
        id          = "file_reader",
        name        = "File Reader",
        description = "Read local or remote files and pipe the contents into an AI summary pipeline.",
        icon        = Icons.Outlined.FolderOpen,
        category    = "Files",
        tags        = listOf("Webhook", "GET"),
        accentColor = SemanticWarning
    ),
    SkillTemplate(
        id          = "weather",
        name        = "Weather Lookup",
        description = "Fetch current weather conditions for any location via an Open-Meteo or Weather API endpoint.",
        icon        = Icons.Outlined.WbSunny,
        category    = "Research",
        tags        = listOf("API", "GET", "JSON"),
        accentColor = SecondaryAccent
    ),
    SkillTemplate(
        id          = "note_taker",
        name        = "Note Taker",
        description = "Post quick notes to Notion, Obsidian, or any REST-accessible note service via webhook.",
        icon        = Icons.Outlined.Note,
        category    = "Productivity",
        tags        = listOf("Webhook", "POST", "JSON"),
        accentColor = PrimaryAccent
    ),
    SkillTemplate(
        id          = "image_gen",
        name        = "Image Generator",
        description = "Call a Stable Diffusion or DALL-E-compatible API and return the image URL to chat.",
        icon        = Icons.Outlined.Image,
        category    = "Creative",
        tags        = listOf("API", "POST", "JSON"),
        accentColor = Color(0xFFEC4899)
    ),
    SkillTemplate(
        id          = "translator",
        name        = "Translator",
        description = "Translate text using DeepL, LibreTranslate, or any REST translation endpoint.",
        icon        = Icons.Outlined.Translate,
        category    = "Communication",
        tags        = listOf("API", "POST", "JSON"),
        accentColor = SemanticSuccess
    ),
    SkillTemplate(
        id          = "task_creator",
        name        = "Task Creator",
        description = "Automatically create tasks in Todoist, Linear, or Jira via their REST APIs.",
        icon        = Icons.Outlined.TaskAlt,
        category    = "Productivity",
        tags        = listOf("Webhook", "POST"),
        accentColor = CosmicAccent
    )
)

private val CATEGORIES = listOf("All") + TEMPLATES.map { it.category }.distinct().sorted()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
    onUseTemplate: (String) -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(selectedCategory, searchQuery) {
        TEMPLATES.filter { t ->
            (selectedCategory == "All" || t.category == selectedCategory) &&
            (searchQuery.isBlank() ||
                t.name.contains(searchQuery, ignoreCase = true) ||
                t.description.contains(searchQuery, ignoreCase = true) ||
                t.tags.any { it.contains(searchQuery, ignoreCase = true) })
        }
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
                title = {
                    Column {
                        Text("Skill Templates", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Pick a template to pre-fill the Skill Builder", color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value         = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder   = { Text("Search templates…", color = Color.White.copy(alpha = 0.35f), fontSize = 13.sp) },
                    leadingIcon   = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.45f)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    shape         = RoundedCornerShape(14.dp),
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = PrimaryAccent,
                        unfocusedBorderColor = BorderLight,
                        focusedTextColor     = Color.White,
                        unfocusedTextColor   = Color.White,
                        cursorColor          = PrimaryAccent,
                        focusedContainerColor   = Surface2,
                        unfocusedContainerColor = Surface2
                    )
                )
            }

            item {
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CATEGORIES.forEach { cat ->
                        val active = cat == selectedCategory
                        Surface(
                            shape  = RoundedCornerShape(20.dp),
                            color  = if (active) PrimaryAccent.copy(alpha = 0.18f)
                                     else Surface2,
                            border = BorderStroke(
                                1.dp,
                                if (active) PrimaryAccent.copy(alpha = 0.55f) else BorderLight
                            ),
                            modifier = Modifier.clickable { selectedCategory = cat }
                        ) {
                            Text(
                                text     = cat,
                                color    = if (active) PrimaryAccent else Color.White.copy(alpha = 0.55f),
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.SearchOff,
                                contentDescription = null,
                                tint     = Color.White.copy(alpha = 0.22f),
                                modifier = Modifier.size(40.dp)
                            )
                            Text(
                                "No templates match \"$searchQuery\"",
                                color    = Color.White.copy(alpha = 0.38f),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { template ->
                    TemplateCard(template = template, onUse = { onUseTemplate(template.id) })
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun TemplateCard(template: SkillTemplate, onUse: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(template.accentColor.copy(alpha = 0.12f))
                        .border(1.dp, template.accentColor.copy(alpha = 0.28f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector        = template.icon,
                        contentDescription = null,
                        tint               = template.accentColor,
                        modifier           = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        template.name,
                        color      = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize   = 15.sp,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = template.accentColor.copy(alpha = 0.12f),
                        modifier = Modifier.border(
                            1.dp, template.accentColor.copy(alpha = 0.30f), RoundedCornerShape(999.dp)
                        )
                    ) {
                        Text(
                            template.category,
                            color    = template.accentColor,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Text(
                template.description,
                color      = Color.White.copy(alpha = 0.65f),
                fontSize   = 13.sp,
                lineHeight = 19.sp,
                maxLines   = 2,
                overflow   = TextOverflow.Ellipsis
            )

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    template.tags.forEach { tag ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.06f)
                        ) {
                            Text(
                                tag,
                                color    = Color.White.copy(alpha = 0.42f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                Button(
                    onClick = onUse,
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = PrimaryAccent,
                        contentColor   = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier       = Modifier.height(32.dp)
                ) {
                    Text("Use Template", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
