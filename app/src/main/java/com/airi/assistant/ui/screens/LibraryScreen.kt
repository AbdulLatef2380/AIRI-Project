package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.workspace.ArtifactManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val artifactManager = ServiceLocator.artifactManager
    val allArtifacts by artifactManager.allArtifacts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val visibleArtifacts = remember(allArtifacts, query) {
        val normalized = query.trim().lowercase()
        if (normalized.isEmpty()) allArtifacts else allArtifacts.filter {
            it.name.lowercase().contains(normalized) ||
                it.type.name.lowercase().contains(normalized) ||
                it.description.lowercase().contains(normalized)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
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
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search files") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CosmicAccent,
                    unfocusedBorderColor = AiriTheme.outline,
                    focusedTextColor = AiriTheme.onBackground,
                    unfocusedTextColor = AiriTheme.onBackground
                )
            )
            if (visibleArtifacts.isEmpty()) {
                EmptyLibraryState(query.isNotBlank())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(visibleArtifacts, key = { it.id }) { artifact ->
                        LibraryArtifactRow(artifact)
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
            Icon(Icons.Outlined.Description, contentDescription = null, tint = CosmicAccent, modifier = Modifier.size(24.dp))
            Spacer(Modifier.size(10.dp))
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
private fun EmptyLibraryState(isFiltering: Boolean) {
    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Outlined.CollectionsBookmark, contentDescription = null, modifier = Modifier.size(56.dp), tint = AiriTheme.onSurfaceVariant.copy(alpha = 0.4f))
        Spacer(Modifier.size(14.dp))
        Text(if (isFiltering) "No matching files" else "No files yet", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
        Text(if (isFiltering) "Try a different name or type." else "Artifacts created by AIRI will appear here.", fontSize = 14.sp, color = AiriTheme.onSurfaceVariant)
    }
}
