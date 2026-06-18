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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    repository: MarketplaceRepository,
    onBack:     () -> Unit = {}
) {
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

    LaunchedEffect(Unit) { repository.fetchFeatured() }

    LaunchedEffect(snackMessage) {
        snackMessage?.let { snackState.showSnackbar(it); snackMessage = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { scope.launch { repository.checkUpdates() } }) {
                        Icon(Icons.Default.Refresh, "Check updates", tint = AiriTheme.onSurfaceVariant)
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
                listOf("Explore", "Installed (${installed.size})", "Publish").forEachIndexed { i, t ->
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
                                "✓ ${skill.name} installed!"
                            else "Install failed — try again."
                        }
                    }
                )
                1 -> InstalledTab(
                    installed  = installed,
                    onUninstall = { skill ->
                        scope.launch {
                            repository.uninstall(skill.id)
                            snackMessage = "${skill.name} removed."
                        }
                    },
                    onUpdate   = { skill ->
                        scope.launch {
                            repository.update(skill)
                            snackMessage = "✓ ${skill.name} updated to v${skill.version}"
                        }
                    }
                )
                2 -> PublishTab(
                    onPublish = { submission ->
                        scope.launch {
                            val r = repository.publish(submission)
                            snackMessage = if (r is MarketplaceRepository.MarketplaceResult.PublishSuccess)
                                "Submitted! Submission ID: ${r.submissionId.take(12)}"
                            else "Submission failed — check your manifest."
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
                placeholder   = { Text("Search skills…") },
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
                    Text("No skills found", color = AiriTheme.onSurfaceVariant)
                }
            }
        } else {
            // ── Featured section ────────────────────────────────────────
            val featured = catalog.filter { it.isFeatured }
            if (featured.isNotEmpty() && selectedCategory == null && searchQuery.isBlank()) {
                item { Text("⭐ Featured", fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground) }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(featured) { skill ->
                            FeaturedSkillCard(skill, onInstall)
                        }
                    }
                }
                item { Text("All Skills", fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground) }
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
                        Text("Install", color = CosmicAccent, fontSize = 12.sp)
                    }
                } else {
                    Text("Installed", fontSize = 12.sp, color = SemanticSuccess)
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
                    Text("${"%,d".format(skill.stats.installCount)} installs", fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (skill.isInstalled) {
                if (skill.hasUpdate) {
                    OutlinedButton(onClick = { onInstall(skill) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Update", fontSize = 12.sp, color = SemanticWarn)
                    }
                } else {
                    Icon(Icons.Default.CheckCircle, "Installed", Modifier.size(20.dp), tint = SemanticSuccess)
                }
            } else {
                Button(
                    onClick = { onInstall(skill) },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                ) { Text("Install", fontSize = 12.sp) }
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
                Text("No skills installed", color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
                Text("Browse the Explore tab to find skills.", fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
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
                            if (skill.hasUpdate) Badge(containerColor = SemanticWarn) { Text("Update", color = Color.White, fontSize = 9.sp) }
                        }
                        Text("v${skill.installedVersion ?: skill.version} · ${skill.publisher.displayName}", fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (skill.hasUpdate) {
                            IconButton(onClick = { onUpdate(skill) }) {
                                Icon(Icons.Default.SystemUpdate, "Update", tint = SemanticWarn)
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

// ── Publish Tab ───────────────────────────────────────────────────────────────

@Composable
private fun PublishTab(onPublish: (SkillPublisher.SkillSubmission) -> Unit) {
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
            Text("Publish a Skill", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AiriTheme.onBackground)
            Text("Share your skill with the AIRI community.", fontSize = 14.sp, color = AiriTheme.onSurfaceVariant)
        }

        item {
            OutlinedTextField(value = publisherName, onValueChange = { publisherName = it },
                label = { Text("Your Name / Publisher Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = DividerColor))
        }

        item {
            OutlinedTextField(value = repositoryUrl, onValueChange = { repositoryUrl = it },
                label = { Text("Repository URL (GitHub, optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = DividerColor))
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isOpenSource, onCheckedChange = { isOpenSource = it }, colors = CheckboxDefaults.colors(checkedColor = CosmicAccent))
                Text("This skill is open source", color = AiriTheme.onBackground)
            }
        }

        item {
            Text("skill.json Manifest", fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
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
                    Text("Validate")
                }
                OutlinedButton(onClick = { manifestJson = SkillPublisher.TEMPLATE_JSON; validationResult = null }) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reset")
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
                            Text(if (vr.isValid) "Manifest is valid ✓" else "Validation failed", fontWeight = FontWeight.SemiBold,
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
                Text("I agree to the AIRI Marketplace Terms of Service", fontSize = 13.sp, color = AiriTheme.onBackground)
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
                Text("Submit for Review", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
