package com.airi.assistant.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.workspace.ArtifactManager
import com.airi.assistant.workspace.ProjectFileManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val workspaceRuntime = ServiceLocator.workspaceRuntime
    val artifactManager = ServiceLocator.artifactManager
    val projectFileManager = ServiceLocator.projectFileManager
    val scope = rememberCoroutineScope()

    val activeProject by workspaceRuntime.activeSession.collectAsStateWithLifecycle()
    val allArtifacts by artifactManager.allArtifacts.collectAsStateWithLifecycle()
    val allProjectFiles by projectFileManager.files.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selectedFileId by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val projectId = activeProject?.sessionId ?: return@rememberLauncherForActivityResult
        uri?.let { selectedUri ->
            scope.launch { projectFileManager.importFromUri(projectId, selectedUri) }
        }
    }

    val projectId = activeProject?.sessionId
    val normalizedQuery = query.trim()
    val projectFiles = remember(projectId, allProjectFiles, normalizedQuery) {
        projectId?.let(projectFileManager::forProject).orEmpty().filter { file ->
            normalizedQuery.isBlank() ||
                file.name.contains(normalizedQuery, ignoreCase = true) ||
                file.mimeType.contains(normalizedQuery, ignoreCase = true) ||
                file.tags.any { tag -> tag.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val projectArtifacts = remember(projectId, allArtifacts, normalizedQuery) {
        allArtifacts.filter { artifact ->
            artifact.sessionId == projectId && (
                normalizedQuery.isBlank() ||
                    artifact.name.contains(normalizedQuery, ignoreCase = true) ||
                    artifact.type.name.contains(normalizedQuery, ignoreCase = true) ||
                    artifact.description.contains(normalizedQuery, ignoreCase = true)
                )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.library_title), fontWeight = FontWeight.Bold)
                        activeProject?.let { project ->
                            Text(
                                project.name,
                                fontSize = 11.sp,
                                color = AiriTheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = activeProject != null,
                        onClick = { filePicker.launch(arrayOf("*/*")) }
                    ) {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = stringResource(R.string.library_import_file),
                            tint = if (activeProject != null) CosmicAccent else AiriTheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AiriTheme.background,
                    titleContentColor = AiriTheme.onBackground,
                    navigationIconContentColor = AiriTheme.onBackground
                )
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        if (activeProject == null) {
            LibraryNoProjectState()
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.library_search_files)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.outline,
                        focusedTextColor = AiriTheme.onBackground,
                        unfocusedTextColor = AiriTheme.onBackground
                    )
                )
                if (projectFiles.isEmpty() && projectArtifacts.isEmpty()) {
                    EmptyLibraryState(isFiltering = normalizedQuery.isNotBlank())
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (projectFiles.isNotEmpty()) {
                            item(key = "project-files-header") {
                                LibrarySectionHeader(
                                    stringResource(R.string.library_project_files),
                                    projectFiles.size
                                )
                            }
                            items(projectFiles, key = { "project-file-${it.id}" }) { file ->
                                ProjectFileRow(
                                    file = file,
                                    expanded = selectedFileId == file.id,
                                    onToggleExpanded = {
                                        selectedFileId = if (selectedFileId == file.id) null else file.id
                                    },
                                    onToggleFavorite = {
                                        projectFileManager.updateMetadata(
                                            id = file.id,
                                            isFavorite = !file.isFavorite
                                        )
                                    },
                                    onDelete = { projectFileManager.delete(file.id) },
                                    onIndex = {
                                        scope.launch {
                                            ServiceLocator.projectKnowledgeManager.indexProjectFile(file.id)
                                        }
                                    }
                                )
                            }
                        }
                        if (projectArtifacts.isNotEmpty()) {
                            item(key = "artifacts-header") {
                                LibrarySectionHeader(
                                    stringResource(R.string.library_generated_artifacts),
                                    projectArtifacts.size
                                )
                            }
                            items(projectArtifacts, key = { "artifact-${it.id}" }) { artifact ->
                                LibraryArtifactRow(artifact)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySectionHeader(title: String, count: Int) {
    Text(
        text = "$title · $count",
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        color = CosmicAccent,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun ProjectFileRow(
    file: ProjectFileManager.ProjectFile,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onIndex: () -> Unit
) {
    Surface(
        color = AiriTheme.surface,
        shape = AIRIShapes.sm,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Description,
                    contentDescription = null,
                    tint = CosmicAccent,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        file.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AiriTheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${file.mimeType} · ${file.sizeBytes / 1024} KB · ${file.lifecycle.name}",
                        fontSize = 11.sp,
                        color = AiriTheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (file.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(R.string.library_toggle_favorite),
                        tint = if (file.isFavorite) CosmicAccent else AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.delete),
                        tint = AiriTheme.onSurfaceVariant,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(
                            R.string.library_file_state,
                            file.extractionState.name,
                            file.indexState.name
                        ),
                        fontSize = 11.sp,
                        color = AiriTheme.onSurfaceVariant
                    )
                    if (file.previewText.isNotBlank()) {
                        Text(
                            file.previewText,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 8,
                            color = AiriTheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AiriTheme.background, AIRIShapes.xs)
                                .padding(8.dp)
                        )
                    }
                    if (
                        file.extractionState == ProjectFileManager.ExtractionState.EXTRACTED &&
                        file.indexState != ProjectFileManager.IndexState.INDEXED
                    ) {
                        TextButton(onClick = onIndex) {
                            Text(stringResource(R.string.library_index_file), color = CosmicAccent)
                        }
                    }
                    if (file.error.isNotBlank()) {
                        Text(
                            file.error,
                            fontSize = 11.sp,
                            color = AiriTheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryArtifactRow(artifact: ArtifactManager.Artifact) {
    Surface(color = AiriTheme.surface, shape = AIRIShapes.sm, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(artifact.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                Text("${artifact.type.name} · ${artifact.sizeBytes / 1024} KB · v${artifact.version}", fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                if (artifact.description.isNotBlank()) {
                    Text(artifact.description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun LibraryNoProjectState() {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Outlined.CollectionsBookmark,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = AiriTheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.size(14.dp))
        Text(stringResource(R.string.library_select_project_title), fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
        Text(stringResource(R.string.library_select_project_description), fontSize = 14.sp, color = AiriTheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyLibraryState(isFiltering: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(56.dp), tint = AiriTheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(Modifier.size(14.dp))
        Text(
            stringResource(if (isFiltering) R.string.library_no_matching_files else R.string.library_no_files),
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = AiriTheme.onBackground
        )
        Text(
            stringResource(if (isFiltering) R.string.library_no_matching_files_description else R.string.library_no_files_description),
            fontSize = 14.sp,
            color = AiriTheme.onSurfaceVariant
        )
    }
}
