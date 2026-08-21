package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.BuildConfig
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.delay
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

data class ReleaseNote(
    val version:     String,
    val date:        String,
    val isCurrent:   Boolean = false,
    val highlights:  List<Pair<String, String>>,   // emoji to text
    val fixes:       List<String>       = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateScreen(onBack: () -> Unit) {
    val snackbar = remember { SnackbarHostState() }

    var checkState by remember { mutableStateOf<CheckState>(CheckState.Idle) }
    var expandedVersion by remember { mutableStateOf<String?>(BuildConfig.VERSION_NAME) }

    val releaseNotes = remember {
        listOf(
            ReleaseNote(
                version    = BuildConfig.VERSION_NAME,
                date       = "June 2025",
                isCurrent  = true,
                highlights = listOf(
                    "•" to ": RAG memory injection into every prompt — AIRI now remembers across sessions",
                    "" to "Cloud memory sync: long-term memories backed up to Firestore, privacy-gated",
                    "" to "Media Library: unified repository for images, documents, and generated artifacts",
                    "•" to "Skill Registry v2: semver versioning, dependency validation, downgrade protection",
                    "•" to "Dynamic Prompt Engine: 10-slot assembly with token budget enforcement",
                    "" to ": Voice Personalization — pitch, rate, personality presets",
                    "" to "Permissions Screen: full rationale view for all 13+ permissions",
                    "" to "Credits Screen: real-time credit metering and token accounting",
                    "⬆" to "Update System: this screen — release notes and version tracking",
                    "⌨" to "Advanced Input Bar: tool picker, skill picker, plan mode"
                ),
                fixes = listOf(
                    "DownloadResult.Success bug → .Ok fixed",
                    "ActivityCategory enum: all 12 values verified",
                    "Coroutine scope safety: SupervisorJob in all long-lived scopes",
                    "No duplicate symbols across 485+ source files"
                )
            ),
            ReleaseNote(
                version    = "0.9.0",
                date       = "May 2025",
                highlights = listOf(
                    "•" to "ConnectorsScreen: full ViewModel wiring for third-party integrations",
                    "" to "SkillManagerScreen: three sources for skills: storage, GitHub, and in-app creation",
                    "•" to "Model picker: correct selectModel public API",
                    "•" to "Token counter wired end-to-end ViewModel → ChatScreen → TopBar",
                    "" to "ThemePreferences.kt: system/dark/light dynamic theming"
                ),
                fixes = listOf(
                    "StarBackground removed — cleaner Compose layer",
                    "AIRI Mail / Cloud Browser dead settings entries removed",
                    "Voice dead-end snackbar replaced with VOICE_SETTINGS navigation"
                )
            ),
            ReleaseNote(
                version    = "0.8.0",
                date       = "April 2025",
                highlights = listOf(
                    "" to "AiriAccessibilityService: full UI tree scanning and action execution",
                    "" to "Vosk STT integration with VoskModelManager and model download",
                    "" to "IncrementalTtsEngine: streaming sentence-level synthesis",
                    "•" to "PlanGenerator: JSON ActionPlan with multi-step execution",
                    "" to "Room database v3: episodic memory + semantic embeddings"
                ),
                fixes = listOf(
                    "Navigation back stack fixed for all deep links",
                    "Firebase Crashlytics NDK symbols registered"
                )
            )
        )
    }

    LaunchedEffect(checkState) {
        if (checkState == CheckState.Checking) {
            delay(2000)
            checkState = CheckState.UpToDate
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.updates_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost   = { SnackbarHost(snackbar) },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            item {
                UpdateStatusCard(
                    checkState = checkState,
                    onCheckNow = { checkState = CheckState.Checking }
                )
            }
            if (checkState == CheckState.UpdateAvailable) {
                item {
                    UpdateBanner(
                        newVersion = "1.1.0",
                        onInstall  = { checkState = CheckState.UpToDate }
                    )
                }
            }
            item {
                Text(
                    "Release Notes",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground
                )
            }

            items(releaseNotes, key = { it.version }) { release ->
                ReleaseNoteCard(
                    release    = release,
                    isExpanded = expandedVersion == release.version,
                    onToggle   = {
                        expandedVersion = if (expandedVersion == release.version) null else release.version
                    }
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun UpdateStatusCard(checkState: CheckState, onCheckNow: () -> Unit) {
    val rotateAnim = rememberInfiniteTransition(label = "rotate")
    val rotation by rotateAnim.animateFloat(
        initialValue   = 0f,
        targetValue    = 360f,
        animationSpec  = infiniteRepeatable(tween(1000, easing = LinearEasing)),
        label          = "spin"
    )

    Surface(
        shape    = AIRIShapes.lg,
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(64.dp).clip(CircleShape)
                    .background(
                        when (checkState) {
                            CheckState.UpdateAvailable -> CosmicAccent.copy(0.20f)
                            CheckState.UpToDate        -> SemanticSuccess.copy(0.15f)
                            else                       -> AiriTheme.onSurface.copy(0.06f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (checkState) {
                    CheckState.Checking -> Icon(
                        Icons.Filled.Update, null,
                        tint     = CosmicAccent,
                        modifier = Modifier.size(30.dp).rotate(rotation)
                    )
                    CheckState.UpToDate -> Icon(
                        Icons.Filled.CheckCircle, null,
                        tint = SemanticSuccess, modifier = Modifier.size(30.dp)
                    )
                    else -> Icon(
                        Icons.Outlined.Update, null,
                        tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(30.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (checkState) {
                        CheckState.Idle            -> "AIRI ${BuildConfig.VERSION_NAME}"
                        CheckState.Checking        -> "Checking for updates…"
                        CheckState.UpToDate        -> "You're up to date"
                        CheckState.UpdateAvailable -> "Update available"
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground
                )
                Text(
                    text = when (checkState) {
                        CheckState.Idle            -> "Build ${BuildConfig.VERSION_CODE} · Tap to check for updates"
                        CheckState.Checking        -> "Connecting to update server…"
                        CheckState.UpToDate        -> "Build ${BuildConfig.VERSION_CODE} is the latest release"
                        CheckState.UpdateAvailable -> "A new version is ready to install"
                    },
                    fontSize = 12.sp, color = AiriTheme.onSurfaceVariant
                )
            }

            if (checkState == CheckState.Idle || checkState == CheckState.UpToDate) {
                Button(
                    onClick  = onCheckNow,
                    colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape    = AIRIShapes.md
                ) {
                    Text(stringResource(R.string.updates_check), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun UpdateBanner(newVersion: String, onInstall: () -> Unit) {
    Surface(
        shape    = AIRIShapes.md,
        color    = CosmicAccent.copy(0.12f),
        modifier = Modifier.fillMaxWidth().border(1.dp, CosmicAccent.copy(0.40f), AIRIShapes.md)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("", fontSize = 24.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.updates_available_version, newVersion), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = AiriTheme.onBackground)
                Text(stringResource(R.string.updates_install_from_store), fontSize = 12.sp,
                    color = AiriTheme.onSurfaceVariant)
            }
            Button(
                onClick  = onInstall,
                colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                shape    = AIRIShapes.sm,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.updates_install), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ReleaseNoteCard(release: ReleaseNote, isExpanded: Boolean, onToggle: () -> Unit) {
    Surface(
        shape    = AIRIShapes.md,
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().border(
            1.dp,
            if (release.isCurrent) CosmicAccent.copy(0.35f) else Color.Transparent,
            AIRIShapes.md
        )
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "v${release.version}", fontSize = 15.sp,
                            fontWeight = FontWeight.Bold, color = AiriTheme.onBackground
                        )
                        if (release.isCurrent) {
                            Surface(
                                shape = AIRIShapes.xs,
                                color = CosmicAccent.copy(0.15f),
                                modifier = Modifier.border(0.5.dp, CosmicAccent.copy(0.4f), AIRIShapes.xs)
                            ) {
                                Text(
                                    "Current", fontSize = 10.sp, color = CosmicAccent,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Text(release.date, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
                }
                Icon(
                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
            }

            // Body
            AnimatedVisibility(visible = isExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    if (release.highlights.isNotEmpty()) {
                        Text(stringResource(R.string.updates_whats_new), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = CosmicAccent, modifier = Modifier.padding(bottom = 8.dp))
                        release.highlights.forEach { (emoji, text) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(emoji, fontSize = 14.sp, modifier = Modifier.width(20.dp))
                                Text(text, fontSize = 12.sp, color = AiriTheme.onBackground,
                                    lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    if (release.fixes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Divider(color = AiriTheme.outline)
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.updates_bug_fixes), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = SemanticSuccess, modifier = Modifier.padding(bottom = 8.dp))
                        release.fixes.forEach { fix ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("•", fontSize = 12.sp, color = SemanticSuccess)
                                Text(fix, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant,
                                    lineHeight = 17.sp, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class CheckState { Idle, Checking, UpToDate, UpdateAvailable }
