package com.airi.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SyncAlt
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.airi.assistant.R
import com.airi.assistant.connector.ConnectorAccessProfile
import com.airi.assistant.ui.theme.AiriTheme

@Composable
fun ConnectorAccessProfileDialog(
    title: String,
    description: String,
    currentProfile: ConnectorAccessProfile,
    availableProfiles: List<ConnectorAccessProfile>,
    onConfirm: (ConnectorAccessProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    val choices = availableProfiles.distinct()
    var selected by remember(currentProfile, choices) {
        mutableStateOf(currentProfile.takeIf { it in choices } ?: choices.firstOrNull() ?: ConnectorAccessProfile.NOT_CONFIGURED)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AiriTheme.surface,
        title = { Text(title, color = AiriTheme.onSurface, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(description, color = AiriTheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                choices.forEach { profile ->
                    val (labelId, detailId, icon) = profilePresentation(profile)
                    val isSelected = selected == profile
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { selected = profile },
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else AiriTheme.outline,
                        ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                            else AiriTheme.surfaceVariant,
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = isSelected, onClick = { selected = profile })
                            androidx.compose.material3.Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else AiriTheme.onSurfaceVariant,
                            )
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(stringResource(labelId), color = AiriTheme.onSurface, style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(detailId), color = AiriTheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text(
                    stringResource(R.string.connectors_access_profile_confirmation_note),
                    color = AiriTheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected) },
                enabled = selected in choices,
            ) { Text(stringResource(R.string.connectors_access_profile_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

private fun profilePresentation(profile: ConnectorAccessProfile): Triple<Int, Int, ImageVector> = when (profile) {
    ConnectorAccessProfile.NOT_CONFIGURED -> Triple(
        R.string.connectors_access_profile_none,
        R.string.connectors_access_profile_none_description,
        Icons.Outlined.Block,
    )
    ConnectorAccessProfile.READ_ONLY -> Triple(
        R.string.connectors_access_profile_read_only,
        R.string.connectors_access_profile_read_only_description,
        Icons.Outlined.Visibility,
    )
    ConnectorAccessProfile.WRITE_ONLY -> Triple(
        R.string.connectors_access_profile_write_only,
        R.string.connectors_access_profile_write_only_description,
        Icons.Outlined.Edit,
    )
    ConnectorAccessProfile.READ_WRITE -> Triple(
        R.string.connectors_access_profile_read_write,
        R.string.connectors_access_profile_read_write_description,
        Icons.Outlined.SyncAlt,
    )
    ConnectorAccessProfile.FULL_ACCESS -> Triple(
        R.string.connectors_access_profile_full,
        R.string.connectors_access_profile_full_description,
        Icons.Outlined.AdminPanelSettings,
    )
}
