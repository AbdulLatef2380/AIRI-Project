package com.airi.assistant.ui.trace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.airi.assistant.agent.trace.TraceStep

@Composable
fun ToolBadgeIcon(step: TraceStep, modifier: Modifier = Modifier) {
    val (icon, tint) = when (step) {
        is TraceStep.WebSearch -> Icons.Outlined.Language to Color(0xFF4FC3F7)
        is TraceStep.UiInteraction -> Icons.Outlined.TouchApp to Color(0xFFFFB74D)
        is TraceStep.ScreenRead -> Icons.Outlined.Visibility to Color(0xFF81C784)
        is TraceStep.MemoryAccess -> Icons.Outlined.Memory to Color(0xFFCE93D8)
        is TraceStep.DeviceAction -> deviceActionIcon(step.toolName)
        is TraceStep.ConfirmationRequest -> Icons.Outlined.HelpOutline to Color(0xFFFFF176)
        is TraceStep.Planning -> Icons.Outlined.Checklist to Color(0xFF80DEEA)
        is TraceStep.Thinking -> Icons.Outlined.Psychology to Color(0xFFB0BEC5)
        is TraceStep.StepError -> Icons.Outlined.ErrorOutline to Color(0xFFEF5350)
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

private fun deviceActionIcon(toolName: String): Pair<ImageVector, Color> = when (toolName.uppercase()) {
    "CALENDAR_READ", "CALENDAR_CREATE" -> Icons.Outlined.CalendarMonth to Color(0xFF80CBC4)
    "SET_ALARM" -> Icons.Outlined.Alarm to Color(0xFFFFCC02)
    "CREATE_NOTE" -> Icons.Outlined.NoteAdd to Color(0xFFA5D6A7)
    else -> Icons.Outlined.Devices to Color(0xFFB0BEC5)
}
