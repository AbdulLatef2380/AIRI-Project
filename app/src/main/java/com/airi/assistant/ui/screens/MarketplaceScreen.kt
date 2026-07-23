@file:OptIn(ExperimentalMaterial3Api::class)
package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.airi.assistant.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.marketplace.GitHubSkillImporter
import com.airi.assistant.marketplace.MarketplaceRepository
import com.airi.assistant.marketplace.MarketplaceSkill
import com.airi.assistant.marketplace.SkillPublisher
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch

/**
 * MarketplaceScreen — browse, search, install, and manage skills from the
 * AIRI Developer Marketplace.
 *
 * Tabs:
 *  - Explore  → featured skills + category filter + search
 *  - Installed → skills installed on this device
 *  - Publish  → submit a new skill to the marketplace
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    repository:          MarketplaceRepository,
    onBack:              () -> Unit = {},
    onNavigateToWizard:  () -> Unit = {}
) {
    val context   = LocalContext.current
    val scope     = rememberCoroutineScope()
    val catalog   by repository.catalog.collectAsState()
    val installed by repository.installed.collectAsState()
    val isLoading by repository.isLoading.collectAsState()
    val lastError by repository.lastError.collectAsState()

    var selectedTab     by remember { mutableIntStateOf(0) }
    var searchQuery     by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<MarketplaceSkill.Category?>(null) }
    var snackMessage    by remember { mutableStateOf<String?>(null) }

    val snackState = remember { SnackbarHostState() }

    val pendingUpdates = remember(installed) { installed.filter { it.hasUpdate } }

    val tabExplore   = stringResource(R.string.marketplace_tab_explore)
    val tabInstalled = stringResource(R.string.marketplace_tab_installed, installed.size)
    val tabUpdates   = if (pendingUpdates.isNotEmpty()) "Updates (${pendingUpdates.size})" else "Updates"
    val tabPublish   = stringResource(R.string.marketplace_tab_publish)
    val tabImport    = stringResource(R.string.marketplace_tab_import)
    val tabLabels    = listOf(tabExplore, tabInstalled, tabUpdates, tabImport, tabPublish)

    LaunchedEffect(Unit) { repository.fetchFeatured() }

    LaunchedEffect(snackMessage) {
        snackMessage?.let { snackState.showSnackbar(it); snackMessage = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.marketplace_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { scope.launch { repository.checkUpdates() } }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.marketplace_check_updates), tint = AiriTheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        snackbarHost    = { SnackbarHost(snackState) },
        containerColor  = AiriTheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = AiriTheme.background,
                contentColor     = CosmicAccent
            ) {
                tabLabels.forEachIndexed { i, t ->
                    Tab(selected = selectedTab == i, onClick = { selectedTab = i },
                        text = { Text(t, color = if (selectedTab == i) CosmicAccent else AiriTheme.onSurfaceVariant, fontSize = 13.sp) })
                }
            }

            when (selectedTab) {
                0 -> ExploreTab(
                    catalog          = catalog,
                    isLoading        = isLoading,
                    lastError        = lastError,
                    searchQuery      = searchQuery,
                    onSearchChange   = { searchQuery = it },
                    onSearch         = { scope.launch { repository.search(searchQuery, selectedCategory) } },
                    selectedCategory = selectedCategory,
                    onCategorySelect = { selectedCategory = if (selectedCategory == it) null else it },
                    onInstall        = { skill ->
                        scope.launch {
                            val r = repository.install(skill)
                            snackMessage = if (r is MarketplaceRepository.MarketplaceResult.InstallSuccess)
                                context.getString(R.string.marketplace_install_success, skill.name)
                            else context.getString(R.string.marketplace_install_failed)
                        }
                    }
                )
                1 -> InstalledTab(
                    installed  = installed,
                    onUninstall = { skill ->
                        scope.launch {
                            repository.uninstall(skill.id)
                            snackMessage = context.getString(R.string.marketplace_removed, skill.name)
                        }
                    },
                    onUpdate   = { skill ->
                        scope.launch {
                            repository.update(skill)
                            snackMessage = context.getString(R.string.marketplace_updated, skill.name, skill.version)
                        }
                    }
                )
                2 -> UpdatesTab(
                    updates  = pendingUpdates,
                    onUpdate = { skill ->
                        scope.launch {
                            repository.update(skill)
                            snackMessage = context.getString(R.string.marketplace_updated, skill.name, skill.version)
                        }
                    }
                )
                3 -> GitHubImportTab(
                    onImported = { skillName ->
                        snackMessage = context.getString(R.string.marketplace_import_success, skillName)
                    },
                    onError = { msg ->
                        snackMessage = context.getString(R.string.skill_import_github_failed, msg)
                    }
                )
                4 -> PublishTab(
                    onNavigateToWizard = onNavigateToWizard,
                    onPublish = { submission ->
                        scope.launch {
                            val r = repository.publish(submission)
                            snackMessage = if (r is MarketplaceRepository.MarketplaceResult.PublishSuccess)
                                context.getString(R.string.marketplace_submitted, r.submissionId.take(12))
                            else context.getString(R.string.marketplace_submit_failed)
                        }
                    }
                )
            }
        }
    }
}
@Composable
private fun ExploreTab(
    catalog:          List<MarketplaceSkill>,
    isLoading:        Boolean,
    lastError:        String?,
    searchQuery:      String,
    onSearchChange:   (String) -> Unit,
    onSearch:         () -> Unit,
    selectedCategory: MarketplaceSkill.Category?,
    onCategorySelect: (MarketplaceSkill.Category) -> Unit,
    onInstall:        (MarketplaceSkill) -> Unit
) {
    val focusManager = LocalFocusManager.current

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value         = searchQuery,
                onValueChange = onSearchChange,
                placeholder   = { Text(stringResource(R.string.marketplace_search_hint)) },
                leadingIcon   = { Icon(Icons.Default.Search, null) },
                trailingIcon  = if (searchQuery.isNotBlank()) ({
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, "Clear")
                    }
                }) else null,
                singleLine     = true,
                modifier       = Modifier.fillMaxWidth(),
                shape          = AIRIShapes.md,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); focusManager.clearFocus() }),
                colors         = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = CosmicAccent,
                    unfocusedBorderColor = AiriTheme.outline
                )
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(MarketplaceSkill.Category.entries) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick  = { onCategorySelect(cat) },
                        label    = { Text("${cat.emoji} ${cat.label}", fontSize = 12.sp) },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CosmicAccent.copy(0.15f),
                            selectedLabelColor     = CosmicAccent
                        )
                    )
                }
            }
        }
        lastError?.let {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SemanticWarn.copy(0.1f)),
                    shape  = AIRIShapes.md
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WifiOff, null, tint = SemanticWarn)
                        Spacer(Modifier.width(8.dp))
                        Text(it, fontSize = 13.sp, color = AiriTheme.onBackground)
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CosmicAccent)
                }
            }
        } else if (catalog.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.marketplace_no_skills), color = AiriTheme.onSurfaceVariant)
                }
            }
        } else {
            val featured = catalog.filter { it.isFeatured }
            if (featured.isNotEmpty() && selectedCategory == null && searchQuery.isBlank()) {
                item { Text(stringResource(R.string.marketplace_featured), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(featured) { skill ->
                            FeaturedSkillCard(skill, onInstall)
                        }
                    }
                }
                item { Text(stringResource(R.string.marketplace_all_skills), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground) }
            }

            items(
                catalog.filter { skill ->
                    (selectedCategory == null || skill.category == selectedCategory) &&
                    (searchQuery.isBlank() || skill.name.contains(searchQuery, true) || skill.tags.any { it.contains(searchQuery, true) })
                },
                key = { it.id }
            ) { skill ->
                SkillListRow(skill, onInstall)
            }
        }
    }
}

@Composable
private fun FeaturedSkillCard(skill: MarketplaceSkill, onInstall: (MarketplaceSkill) -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape    = AIRIShapes.md,
        modifier = Modifier.width(200.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(36.dp).background(CosmicAccent.copy(0.15f), AIRIShapes.sm),
                    contentAlignment = Alignment.Center
                ) { Text(skill.category.emoji, fontSize = 18.sp) }
                Column {
                    Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(stringResource(R.string.marketplace_version_prefix, skill.version), fontSize = 10.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
            Text(skill.description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Button(
                onClick = { onInstall(skill) },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = AIRIShapes.sm,
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
            ) {
                Text(stringResource(R.string.marketplace_install), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun SkillListRow(skill: MarketplaceSkill, onInstall: (MarketplaceSkill) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(44.dp).background(AiriTheme.surfaceVariant, AIRIShapes.md),
            contentAlignment = Alignment.Center
        ) { Text(skill.category.emoji, fontSize = 20.sp) }
        Column(Modifier.weight(1f)) {
            Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground, fontSize = 14.sp)
            Text(skill.description, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = { onInstall(skill) }) {
            Text(stringResource(R.string.marketplace_install), color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

@Composable
private fun InstalledTab(
    installed: List<MarketplaceSkill>,
    onUninstall: (MarketplaceSkill) -> Unit,
    onUpdate: (MarketplaceSkill) -> Unit
) {
    if (installed.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.marketplace_no_installed), color = AiriTheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(installed) { skill ->
                InstalledSkillRow(skill, onUninstall, onUpdate)
            }
        }
    }
}

@Composable
private fun InstalledSkillRow(
    skill: MarketplaceSkill,
    onUninstall: (MarketplaceSkill) -> Unit,
    onUpdate: (MarketplaceSkill) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape = AIRIShapes.md
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(AiriTheme.background, AIRIShapes.sm),
                contentAlignment = Alignment.Center
            ) { Text(skill.category.emoji, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground, fontSize = 14.sp)
                Text(stringResource(R.string.marketplace_version_prefix, skill.version), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
            }
            if (skill.hasUpdate) {
                IconButton(onClick = { onUpdate(skill) }) {
                    Icon(Icons.Default.Update, null, tint = CosmicAccent)
                }
            }
            IconButton(onClick = { onUninstall(skill) }) {
                Icon(Icons.Default.DeleteOutline, null, tint = SemanticError)
            }
        }
    }
}

@Composable
private fun UpdatesTab(
    updates: List<MarketplaceSkill>,
    onUpdate: (MarketplaceSkill) -> Unit
) {
    if (updates.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.CheckCircle, null, tint = SemanticSuccess, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.marketplace_up_to_date), color = AiriTheme.onSurfaceVariant)
            }
        }
    } else {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(updates) { skill ->
                UpdateSkillRow(skill, onUpdate)
            }
        }
    }
}

@Composable
private fun UpdateSkillRow(skill: MarketplaceSkill, onUpdate: (MarketplaceSkill) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape = AIRIShapes.md
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(AiriTheme.background, AIRIShapes.sm),
                contentAlignment = Alignment.Center
            ) { Text(skill.category.emoji, fontSize = 18.sp) }
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground, fontSize = 14.sp)
                Text(stringResource(R.string.marketplace_update_available, skill.version), fontSize = 11.sp, color = CosmicAccent)
            }
            Button(
                onClick = { onUpdate(skill) },
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape = AIRIShapes.sm,
                modifier = Modifier.height(36.dp)
            ) {
                Text(stringResource(R.string.marketplace_update), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun GitHubImportTab(
    onImported: (String) -> Unit,
    onError: (String) -> Unit
) {
    var repoUrl by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.marketplace_import_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
        Text(stringResource(R.string.marketplace_import_subtitle), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 18.sp)
        
        OutlinedTextField(
            value = repoUrl,
            onValueChange = { repoUrl = it },
            placeholder = { Text("https://github.com/user/skill-repo") },
            modifier = Modifier.fillMaxWidth(),
            shape = AIRIShapes.md,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent)
        )
        
        Button(
            onClick = {
                if (repoUrl.isNotBlank()) {
                    isImporting = true
                    scope.launch {
                        val result = GitHubSkillImporter.importFromUrl(repoUrl)
                        isImporting = false
                        if (result.success) {
                            onImported(result.skill?.id ?: "skill")
                            repoUrl = ""
                        } else {
                            onError(result.errors.joinToString("; "))
                        }
                    }
                }
            },
            enabled = repoUrl.isNotBlank() && !isImporting,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
            shape = AIRIShapes.md
        ) {
            if (isImporting) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White)
            else Text(stringResource(R.string.marketplace_import_button), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PublishTab(
    onNavigateToWizard: () -> Unit,
    onPublish: (SkillPublisher.SkillSubmission) -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.CloudUpload, null, modifier = Modifier.size(64.dp), tint = CosmicAccent.copy(0.2f))
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.marketplace_publish_subtitle), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create and share your custom skills with the AIRI community.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = AiriTheme.onSurfaceVariant,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onNavigateToWizard,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
            shape = AIRIShapes.md
        ) {
            Text(stringResource(R.string.marketplace_submit_review), fontWeight = FontWeight.Bold)
        }
    }
}
