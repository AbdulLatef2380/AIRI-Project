package com.airi.assistant.ui.run

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import kotlinx.coroutines.delay

private val CardOk = Color(0xFF2E9E6B)
private val CardErr = Color(0xFFFF5252)

/**
 * Part 5 §1: the run card shown under an assistant message in the chat. It replaces the old
 * "Agent · N" badge, shows the real step count and opens the full run screen. The host decides
 * whether to show it: [buildRunCardModel] returns null for a conversation without tools.
 */
@Composable
fun RunCard(model: RunCardModel, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    val statusColor = when (model.status) {
        RunCardStatus.RUNNING -> CosmicAccent
        RunCardStatus.COMPLETED -> CardOk
        RunCardStatus.FAILED -> CardErr
    }
    val statusText = stringResource(
        when (model.status) {
            RunCardStatus.RUNNING -> R.string.run_status_running
            RunCardStatus.COMPLETED -> R.string.run_status_completed
            RunCardStatus.FAILED -> R.string.run_status_failed
        }
    )
    // Live timer only while running; a finished run shows its recorded duration instead.
    val elapsedMs by produceState(
        initialValue = model.elapsedMs ?: (System.currentTimeMillis() - model.startedAtMs).coerceAtLeast(0L),
        model.runId, model.status,
    ) {
        if (model.status == RunCardStatus.RUNNING) {
            while (true) {
                value = (System.currentTimeMillis() - model.startedAtMs).coerceAtLeast(0L)
                delay(1_000)
            }
        } else {
            value = model.elapsedMs ?: 0L
        }
    }
    val timer = DateUtils.formatElapsedTime((elapsedMs / 1000L).coerceAtLeast(0L))
    val steps = pluralStringResource(R.plurals.run_card_steps, model.stepCount, model.stepCount)
    val description = stringResource(R.string.run_card_cd, model.title, "$statusText, $steps")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CosmicAccent.copy(alpha = 0.07f))
            .border(0.5.dp, statusColor.copy(alpha = 0.4f), shape)
            .clickable(onClick = onOpen)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.AutoAwesome, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                model.title, color = AiriTheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.status == RunCardStatus.RUNNING) {
                    CircularProgressIndicator(Modifier.size(11.dp), strokeWidth = 1.5.dp, color = CosmicAccent)
                }
                Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(steps, color = AiriTheme.onSurfaceVariant, fontSize = 11.sp)
                if (elapsedMs > 0L || model.status == RunCardStatus.RUNNING) {
                    Text(timer, color = AiriTheme.outline, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.run_card_open), color = CosmicAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
