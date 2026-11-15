package com.airi.assistant.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.workspace.ArtifactManager
import java.util.Locale

@Composable
fun ArtifactReferenceCards(
    artifacts: List<ArtifactManager.Artifact>,
    onPreview: (String) -> Unit,
    unresolvedReferenceCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (artifacts.isEmpty() && unresolvedReferenceCount == 0) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (unresolvedReferenceCount > 0) {
            val loadFailedDescription = stringResource(R.string.artifact_load_failed)
            Text(
                text = loadFailedDescription,
                color = AiriTheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = loadFailedDescription },
            )
        }
        artifacts.forEach { artifact ->
            val description = stringResource(
                R.string.artifact_size_version,
                artifact.name,
                artifact.sizeBytes / 1024,
                artifact.version.toString(),
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPreview(artifact.id) }
                    .semantics { contentDescription = description },
                shape = AIRIShapes.md,
                color = AiriTheme.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.Description, contentDescription = null, tint = AiriTheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(artifact.name, color = AiriTheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            text = "${artifact.type.name} · ${formatArtifactSize(artifact.sizeBytes)} · v${artifact.version}",
                            color = AiriTheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Outlined.Visibility, contentDescription = stringResource(R.string.workspace_preview), tint = AiriTheme.primary)
                }
            }
        }
    }
}

private fun formatArtifactSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}
