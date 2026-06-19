@file:OptIn(ExperimentalMaterial3Api::class)
package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LocalTextStyle
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

    val tabExplore   = stringResource(R.string.marketplace_tab_explore)
    val tabInstalled = stringResource(R.string.marketplace_tab_installed, installed.size)
    val tabPublish   = stringResource(R.string.marketplace_tab_publish)
    val tabImport    = stringResource(R.string.marketplace_tab_import)
    val tabLabels    = listOf(tabExplore, tabInstalled, tabImport, tabPublish)

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
                2 -> GitHubImportTab(
                    onImported = { skillName ->
                        snackMessage = context.getString(R.string.marketplace_import_success, skillName)
                    },
                    onError = { msg ->
                        snackMessage = context.getString(R.string.skill_import_github_failed, msg)
                    }
                )
                3 -> PublishTab(
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

// ── Explore Tab ───────────────────────────────────────────────────────────────

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
        // ── Search bar ──────────────────────────────────────────────────
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
                shape          = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(); focusManager.clearFocus() }),
                colors         = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = CosmicAccent,
                    unfocusedBorderColor = DividerColor
                )
            )
        }

        // ── Category chips ───────────────────────────────────────────────
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

        // ── Error / loading ──────────────────────────────────────────────
        lastError?.let {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SemanticWarn.copy(0.1f)),
                    shape  = RoundedCornerShape(12.dp)
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
            // ── Featured section ────────────────────────────────────────
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
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.width(200.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(36.dp).background(CosmicAccent.copy(0.15f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(skill.category.emoji, fontSize = 18.sp) }
                Column {
                    Text(skill.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AiriTheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(skill.publisher.displayName, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(skill.description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(skill.ratingStars, fontSize = 11.sp, color = Color(0xFFFFC107))
                if (!skill.isInstalled) {
                    TextButton(onClick = { onInstall(skill) }, contentPadding = PaddingValues(0.dp)) {
                        Text(stringResource(R.string.marketplace_install), color = CosmicAccent, fontSize = 12.sp)
                    }
                } else {
                    Text(stringResource(R.string.marketplace_installed_badge), fontSize = 12.sp, color = SemanticSuccess)
                }
            }
        }
    }
}

@Composable
private fun SkillListRow(skill: MarketplaceSkill, onInstall: (MarketplaceSkill) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(CosmicAccent.copy(0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) { Text(skill.category.emoji, fontSize = 22.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AiriTheme.onBackground)
                    if (skill.isVerified) Icon(Icons.Default.Verified, "Verified", Modifier.size(14.dp), tint = CosmicAccent)
                }
                Text("${skill.publisher.displayName} · ${skill.displayVersion}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                Text(skill.description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(skill.ratingStars, fontSize = 11.sp, color = Color(0xFFFFC107))
                    Text(stringResource(R.string.marketplace_installs_count, "%,d".format(skill.stats.installCount)), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (skill.isInstalled) {
                if (skill.hasUpdate) {
                    OutlinedButton(onClick = { onInstall(skill) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(stringResource(R.string.marketplace_update), fontSize = 12.sp, color = SemanticWarn)
                    }
                } else {
                    Icon(Icons.Default.CheckCircle, stringResource(R.string.marketplace_installed_badge), Modifier.size(20.dp), tint = SemanticSuccess)
                }
            } else {
                Button(
                    onClick = { onInstall(skill) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                ) { Text(stringResource(R.string.marketplace_install), fontSize = 12.sp) }
            }
        }
    }
}

// ── Installed Tab ─────────────────────────────────────────────────────────────

@Composable
private fun InstalledTab(
    installed:  List<MarketplaceSkill>,
    onUninstall: (MarketplaceSkill) -> Unit,
    onUpdate:   (MarketplaceSkill) -> Unit
) {
    if (installed.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Extension, null, Modifier.size(56.dp), tint = AiriTheme.onSurfaceVariant)
                Text(stringResource(R.string.marketplace_no_installed), color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.marketplace_explore_hint), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        items(installed, key = { it.id }) { skill ->
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = RoundedCornerShape(14.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(skill.name, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                            if (skill.hasUpdate) Badge(containerColor = SemanticWarn) { Text(stringResource(R.string.marketplace_update), color = Color.White, fontSize = 9.sp) }
                        }
                        Text("v${skill.installedVersion ?: skill.version} · ${skill.publisher.displayName}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (skill.hasUpdate) {
                            IconButton(onClick = { onUpdate(skill) }) {
                                Icon(Icons.Default.SystemUpdate, stringResource(R.string.marketplace_update), tint = SemanticWarn)
                            }
                        }
                        IconButton(onClick = { onUninstall(skill) }) {
                            Icon(Icons.Default.Delete, "Uninstall", tint = SemanticError)
                        }
                    }
                }
            }
        }
    }
}

// ── GitHub Import Tab ─────────────────────────────────────────────────────────

@Composable
private fun GitHubImportTab(
    onImported: (String) -> Unit,
    onError:    (String) -> Unit
) {
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    var url          by remember { mutableStateOf("") }
    var isLoading    by remember { mutableStateOf(false) }
    var result       by remember { mutableStateOf<GitHubSkillImporter.ImportResult?>(null) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                stringResource(R.string.marketplace_import_title),
                fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AiriTheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.marketplace_import_subtitle),
                fontSize = 14.sp, color = AiriTheme.onSurfaceVariant
            )
        }

        item {
            OutlinedTextField(
                value         = url,
                onValueChange = { url = it; result = null },
                label         = { Text(stringResource(R.string.marketplace_import_url_label)) },
                placeholder   = { Text("https://github.com/user/my-skill", color = AiriTheme.onSurfaceVariant.copy(0.5f)) },
                modifier      = Modifier.fillMaxWidth(),
                singleLine    = true,
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = CosmicAccent,
                    unfocusedBorderColor = DividerColor
                )
            )
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                shape  = RoundedCornerShape(12.dp)
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.marketplace_import_formats_title), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = AiriTheme.onBackground)
                    Text("• https://github.com/user/repo", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    Text("• https://raw.githubusercontent.com/user/repo/main/skill.json", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
        }

        item {
            Button(
                onClick = {
                    if (url.isBlank()) { onError("Please enter a URL"); return@Button }
                    isLoading = true
                    result    = null
                    scope.launch {
                        val r = GitHubSkillImporter.importFromUrl(url.trim())
                        result    = r
                        isLoading = false
                        if (r.success && r.skill != null) {
                            onImported(r.manifest?.name ?: r.skill.name)
                        } else if (!r.success) {
                            onError(r.errors.firstOrNull() ?: "Import failed")
                        }
                    }
                },
                enabled  = url.isNotBlank() && !isLoading,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = RoundedCornerShape(14.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.marketplace_import_loading))
                } else {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.marketplace_import_button), fontWeight = FontWeight.SemiBold)
                }
            }
        }

        result?.let { r ->
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.success) SemanticSuccess.copy(0.1f) else SemanticError.copy(0.1f)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                if (r.success) Icons.Default.CheckCircle else Icons.Default.Error,
                                null, Modifier.size(18.dp),
                                tint = if (r.success) SemanticSuccess else SemanticError
                            )
                            Text(
                                if (r.success) stringResource(R.string.marketplace_import_success_label, r.manifest?.name ?: "")
                                else           stringResource(R.string.marketplace_import_error_label),
                                fontWeight = FontWeight.SemiBold,
                                color      = if (r.success) SemanticSuccess else SemanticError
                            )
                        }
                        if (r.success) {
                            r.manifest?.let { m ->
                                Text("ID: ${m.id}  •  v${m.version}  •  by ${m.author}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                                Text(m.description, fontSize = 13.sp, color = AiriTheme.onBackground)
                                if (m.tools.isNotEmpty()) {
                                    Text("Tools: ${m.tools.joinToString { it.name }}", fontSize = 12.sp, color = CosmicAccent)
                                }
                            }
                        }
                        r.errors.forEach   { Text("• $it", fontSize = 12.sp, color = SemanticError) }
                        r.warnings.forEach { Text("⚠ $it", fontSize = 12.sp, color = SemanticWarn) }
                    }
                }
            }
        }
    }
}

// ── Publish Tab ───────────────────────────────────────────────────────────────

@Composable
private fun PublishTab(
    onNavigateToWizard: () -> Unit,
    onPublish: (SkillPublisher.SkillSubmission) -> Unit
) {
    var manifestJson   by remember { mutableStateOf(SkillPublisher.TEMPLATE_JSON) }
    var publisherName  by remember { mutableStateOf("") }
    var repositoryUrl  by remember { mutableStateOf("") }
    var licenseId      by remember { mutableStateOf("MIT") }
    var isOpenSource   by remember { mutableStateOf(true) }
    var termsAccepted  by remember { mutableStateOf(false) }
    var validationResult by remember { mutableStateOf<SkillPublisher.ValidationResult?>(null) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            Text(stringResource(R.string.marketplace_publish_title), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AiriTheme.onBackground)
            Text(stringResource(R.string.marketplace_publish_subtitle), fontSize = 14.sp, color = AiriTheme.onSurfaceVariant)
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(CosmicAccent.copy(alpha = 0.10f))
                    .border(1.dp, CosmicAccent.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(28.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Create with Wizard",
                        fontWeight = FontWeight.Bold,
                        color = CosmicAccent,
                        fontSize = 15.sp
                    )
                    Text(
                        "Build a skill.json manifest step-by-step instead of editing raw JSON.",
                        fontSize = 12.sp,
                        color = AiriTheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onNavigateToWizard,
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Open", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        item {
            OutlinedTextField(value = publisherName, onValueChange = { publisherName = it },
                label = { Text(stringResource(R.string.marketplace_publisher_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = DividerColor))
        }

        item {
            OutlinedTextField(value = repositoryUrl, onValueChange = { repositoryUrl = it },
                label = { Text(stringResource(R.string.marketplace_repo_url)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = DividerColor))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isOpenSource, onCheckedChange = { isOpenSource = it }, colors = CheckboxDefaults.colors(checkedColor = CosmicAccent))
                Text(stringResource(R.string.marketplace_open_source), color = AiriTheme.onBackground)
            }
        }

        item {
            Text(stringResource(R.string.marketplace_manifest_label), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value         = manifestJson,
                onValueChange = { manifestJson = it; validationResult = null },
                modifier      = Modifier.fillMaxWidth().height(240.dp),
                textStyle     = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                colors        = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = DividerColor)
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { validationResult = SkillPublisher.validateManifest(manifestJson) }) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.marketplace_validate))
                }
                OutlinedButton(onClick = { manifestJson = SkillPublisher.TEMPLATE_JSON; validationResult = null }) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.marketplace_reset))
                }
            }
        }

        validationResult?.let { vr ->
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (vr.isValid) SemanticSuccess.copy(0.1f) else SemanticError.copy(0.1f)),
                    shape  = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (vr.isValid) Icons.Default.CheckCircle else Icons.Default.Error, null,
                                Modifier.size(16.dp), tint = if (vr.isValid) SemanticSuccess else SemanticError)
                            Spacer(Modifier.width(6.dp))
                            Text(if (vr.isValid) stringResource(R.string.marketplace_manifest_valid) else stringResource(R.string.marketplace_manifest_invalid), fontWeight = FontWeight.SemiBold,
                                color = if (vr.isValid) SemanticSuccess else SemanticError)
                        }
                        vr.errors.forEach   { Text("• $it", fontSize = 12.sp, color = SemanticError) }
                        vr.warnings.forEach { Text("⚠ $it", fontSize = 12.sp, color = SemanticWarn) }
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = termsAccepted, onCheckedChange = { termsAccepted = it }, colors = CheckboxDefaults.colors(checkedColor = CosmicAccent))
                Text(stringResource(R.string.marketplace_terms), fontSize = 13.sp, color = AiriTheme.onBackground)
            }
        }

        item {
            Button(
                onClick = {
                    val submission = SkillPublisher.buildSubmission(
                        manifestJson  = manifestJson,
                        publisherName = publisherName,
                        publisherId   = publisherName.lowercase().replace(" ", "_"),
                        repositoryUrl = repositoryUrl.ifBlank { null },
                        licenseId     = licenseId,
                        isOpenSource  = isOpenSource,
                        termsAccepted = termsAccepted
                    ) ?: return@Button
                    onPublish(submission)
                },
                enabled  = termsAccepted && publisherName.isNotBlank() && (validationResult?.isValid == true || validationResult == null),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Default.Publish, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.marketplace_submit_review), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
