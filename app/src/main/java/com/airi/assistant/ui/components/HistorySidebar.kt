package com.airi.assistant.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.memory.dao.ChatSessionSummary
import com.airi.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * HistorySidebar — slide-in overlay from the right showing past chat sessions.
 *
 * Rendered inside a [Box] that spans the full screen.  The translucent scrim
 * covers the content area; tapping it closes the sidebar.
 *
 * The sidebar occupies ~82 % of screen width to match the React prototype layout.
 */
@Composable
fun HistorySidebar(
    visible:          Boolean,
    sessions:         List<ChatSessionSummary>,
    currentSessionId: String,
    onClose:          () -> Unit,
    onNewChat:        () -> Unit,
    onSessionSelect:  (String) -> Unit,
    onSessionDelete:  (String) -> Unit,
    modifier:         Modifier = Modifier
) {
    AnimatedVisibility(
        visible              = visible,
        enter                = fadeIn(tween(180)),
        exit                 = fadeOut(tween(180)),
        modifier             = modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // ── Scrim ────────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
            )

            // ── Panel ────────────────────────────────────────────────────────
            AnimatedVisibility(
                visible  = visible,
                enter    = slideInHorizontally(tween(240)) { it },
                exit     = slideOutHorizontally(tween(220)) { it },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Surface1)
                        .border(
                            width = 1.dp,
                            color = BorderLight,
                            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                        )
                        .pointerInput(Unit) { detectTapGestures { /* consume */ } }
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text       = "المحادثات",
                            color      = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize   = 17.sp,
                            modifier   = Modifier.weight(1f)
                        )
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "إغلاق", tint = TextSecondary)
                        }
                    }

                    // New Chat button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(PrimaryAccent.copy(alpha = 0.12f))
                            .border(1.dp, PrimaryAccent.copy(alpha = 0.30f), RoundedCornerShape(10.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication        = null,
                                onClick           = { onNewChat(); onClose() }
                            )
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(16.dp))
                            Text("محادثة جديدة", color = PrimaryAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(BorderLight))
                    Spacer(Modifier.height(4.dp))

                    // Session list
                    if (sessions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint     = TextTertiary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Text("لا توجد محادثات بعد", color = TextTertiary, fontSize = 13.sp)
                            }
                        }
                    } else {
                        val grouped = sessions.groupBy { session ->
                            groupLabel(session.updatedAt)
                        }

                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp)
                        ) {
                            grouped.forEach { (label, group) ->
                                item {
                                    Text(
                                        text     = label,
                                        color    = TextTertiary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                                    )
                                }
                                items(group, key = { it.id }) { session ->
                                    SessionItem(
                                        session   = session,
                                        isActive  = session.id == currentSessionId,
                                        onSelect  = { onSessionSelect(session.id); onClose() },
                                        onDelete  = { onSessionDelete(session.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    session:  ChatSessionSummary,
    isActive: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) PrimaryAccent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Active indicator
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(width = 3.dp, height = 30.dp)
                    .clip(CircleShape)
                    .background(PrimaryAccent)
            )
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(13.dp))
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text       = session.title.ifBlank { "محادثة" },
                color      = if (isActive) PrimaryAccent else TextPrimary,
                fontSize   = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis
            )
            Text(
                text     = formatDate(session.updatedAt),
                color    = TextTertiary,
                fontSize = 11.sp
            )
        }

        IconButton(
            onClick  = onDelete,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "حذف", tint = TextTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

private fun groupLabel(epochMs: Long): String {
    val now     = Calendar.getInstance()
    val date    = Calendar.getInstance().also { it.timeInMillis = epochMs }
    val diffMs  = now.timeInMillis - epochMs
    val diffDays = (diffMs / (1000 * 60 * 60 * 24)).toInt()

    return when {
        diffDays == 0 -> "اليوم"
        diffDays == 1 -> "أمس"
        diffDays < 7  -> "هذا الأسبوع"
        now.get(Calendar.MONTH) == date.get(Calendar.MONTH) &&
            now.get(Calendar.YEAR) == date.get(Calendar.YEAR) -> "هذا الشهر"
        else -> SimpleDateFormat("MMMM yyyy", Locale("ar")).format(Date(epochMs))
    }
}

private fun formatDate(epochMs: Long): String {
    val now    = System.currentTimeMillis()
    val diffMs = now - epochMs
    return when {
        diffMs < 60_000               -> "الآن"
        diffMs < 3_600_000            -> "${diffMs / 60_000} د"
        diffMs < 86_400_000           -> "${diffMs / 3_600_000} س"
        diffMs < 2 * 86_400_000       -> "أمس"
        else -> SimpleDateFormat("d MMM", Locale("ar")).format(Date(epochMs))
    }
}
