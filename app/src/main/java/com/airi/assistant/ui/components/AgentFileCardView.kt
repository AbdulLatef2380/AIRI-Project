package com.airi.assistant.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.agent.loop.tool.AgentFileCard
import com.airi.assistant.agent.loop.tool.AgentFileExporter
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1, item 7: files the agent wrote during a turn, with Save / Share / Open actions.
 * Renders nothing for an empty list, so a plain chat reply never shows a card.
 */
@Composable
fun AgentFileCards(
    cards: List<AgentFileCard>,
    modifier: Modifier = Modifier,
) {
    if (cards.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cards.forEach { card -> key(card.fileName) { AgentFileCardView(card) } }
    }
}

@Composable
private fun AgentFileCardView(card: AgentFileCard) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val kindLabel = stringResource(
        when (card.kind) {
            AgentFileCard.Kind.CODE -> R.string.agent_file_kind_code
            AgentFileCard.Kind.TEXT -> R.string.agent_file_kind_text
            AgentFileCard.Kind.DATA -> R.string.agent_file_kind_data
        }
    )
    val description = stringResource(R.string.agent_file_card_description, card.fileName, kindLabel, card.displaySize)

    Surface(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = description },
        shape = AIRIShapes.md,
        color = AiriTheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, AiriTheme.outline.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = CosmicAccent.copy(alpha = 0.16f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = when (card.kind) {
                                AgentFileCard.Kind.CODE -> Icons.Outlined.Code
                                AgentFileCard.Kind.TEXT -> Icons.Outlined.Description
                                AgentFileCard.Kind.DATA -> Icons.Outlined.Storage
                            },
                            contentDescription = null,
                            tint = CosmicAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.fileName,
                        color = AiriTheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "$kindLabel · ${card.displaySize}",
                        color = AiriTheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                CardAction(Icons.Outlined.FileDownload, stringResource(R.string.agent_file_save)) {
                    scope.launch { saveFile(context, card) }
                }
                CardAction(Icons.Outlined.Share, stringResource(R.string.agent_file_share)) {
                    launchChooser(context, AgentFileExporter.shareIntent(context, card))
                }
                CardAction(Icons.Outlined.FolderOpen, stringResource(R.string.agent_file_open)) {
                    launchChooser(context, AgentFileExporter.openIntent(context, card))
                }
            }
        }
    }
}

@Composable
private fun CardAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = CosmicAccent)
        Spacer(Modifier.width(4.dp))
        Text(label, color = CosmicAccent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private suspend fun saveFile(context: Context, card: AgentFileCard) {
    val result = withContext(Dispatchers.IO) { AgentFileExporter.saveToDownloads(context, card) }
    val message = when (result) {
        AgentFileExporter.SaveResult.SAVED -> context.getString(R.string.agent_file_saved_downloads, card.fileName)
        AgentFileExporter.SaveResult.UNAVAILABLE -> context.getString(R.string.agent_file_save_use_share)
        AgentFileExporter.SaveResult.FAILED -> context.getString(R.string.agent_file_save_failed)
    }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}

private fun launchChooser(context: Context, intent: Intent?) {
    if (intent == null) {
        Toast.makeText(context, context.getString(R.string.agent_file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.agent_file_action_failed), Toast.LENGTH_SHORT).show()
    }
}
