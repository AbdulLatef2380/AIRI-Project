package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatInputSuggestion
import com.airi.assistant.ui.viewmodel.ModelUiState

/**
 * AdvancedChatInputBar —  replacement for AiriChatInputBar.
 *
 * Adds a dynamic action toolbar above the existing input bar:
 *  - Tool Picker: quick access to active tools (web, calculator, calendar, code)
 *  - Skill Picker: browse and activate installed skills
 *  - Plan Mode: toggle step-by-step agent planning before execution
 *  - Dynamic action chips that adapt to context
 *
 * All existing AiriChatInputBar parameters are passed through unchanged.
 */
@Composable
fun AdvancedChatInputBar(
    modelState:             ModelUiState,
    isGenerating:           Boolean,
    voiceInput:             String,
    voiceState:             VoiceSessionState       = VoiceSessionState.IDLE,
    isVadInterrupting:      Boolean                 = false,
    smartReplies:           List<String>            = emptyList(),
    onSend:                 (String) -> Unit,
    onCancel:               () -> Unit              = {},
    onSmartReply:           (String) -> Unit        = {},
    onPickImage:            () -> Unit              = {},
    onPickMmproj:           () -> Unit              = {},
    onPickFile:             () -> Unit              = {},
    onTakePhoto:            () -> Unit              = {},
    onMicClick:             () -> Unit,
    onVoiceChatClick:       () -> Unit,
    onVoiceConsumed:        () -> Unit,
    onOpenModels:           () -> Unit,
    onNavigate:             (String) -> Unit        = {},
    // : called when user converts large prompt to attached file
    onStageFile:            (android.net.Uri) -> Unit = {},
    externalInputText:      String?                 = null,
    onExternalInputConsumed: () -> Unit             = {},
    onUserStartedTyping:    () -> Unit              = {},
    onOpenToolPicker:       () -> Unit              = {},
    onOpenSkillPicker:      () -> Unit              = {},
    isPlanModeActive:       Boolean                 = false,
    onPlanModeToggle:       () -> Unit              = {},
    activeToolCount:        Int                     = 0,
    activeSkillCount:       Int                     = 0,
    onWebClick:             () -> Unit              = {},
    onCodeClick:            () -> Unit              = {},
    onCalcClick:            () -> Unit              = {},
    skillSuggestions:       List<ChatInputSuggestion> = emptyList(),
    knowledgeSuggestions:   List<ChatInputSuggestion> = emptyList(),
    onSkillQueryChanged:    (String) -> Unit         = {},
    onKnowledgeQueryChanged:(String) -> Unit         = {},
    // Attachments inside the pill
    attachments:            List<com.airi.assistant.domain.ChatAttachment> = emptyList(),
    onRemoveAttachment:     (String) -> Unit        = {}
) {
    // Track focus state to collapse toolbar when idle
    var hasFocus by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = hasFocus || isGenerating || isPlanModeActive,
            enter   = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit    = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
        ) {
            InputActionToolbar(
                isPlanModeActive  = isPlanModeActive,
                onPlanModeToggle  = onPlanModeToggle,
                onOpenToolPicker  = onOpenToolPicker,
                onOpenSkillPicker = onOpenSkillPicker,
                activeToolCount   = activeToolCount,
                activeSkillCount  = activeSkillCount,
                isGenerating      = isGenerating,
                onWebClick        = onWebClick,
                onCodeClick       = onCodeClick,
                onCalcClick       = onCalcClick,
                onTakePhoto       = onTakePhoto,
                onPickFile        = onPickFile
            )
        }
        AiriChatInputBar(
            modelState              = modelState,
            isGenerating            = isGenerating,
            voiceInput              = voiceInput,
            voiceState              = voiceState,
            isVadInterrupting       = isVadInterrupting,
            smartReplies            = smartReplies,
            onSend                  = onSend,
            onCancel                = onCancel,
            onSmartReply            = onSmartReply,
            onPickImage             = onPickImage,
            onPickMmproj            = onPickMmproj,
            onPickFile              = onPickFile,
            onTakePhoto             = onTakePhoto,
            onMicClick              = onMicClick,
            onVoiceChatClick        = onVoiceChatClick,
            onVoiceConsumed         = onVoiceConsumed,
            onOpenModels            = onOpenModels,
            onNavigate              = onNavigate,
            onStageFile             = onStageFile,
            externalInputText       = externalInputText,
            onExternalInputConsumed = onExternalInputConsumed,
            onUserStartedTyping     = onUserStartedTyping,
            onFocusChanged          = { hasFocus = it },
            skillSuggestions         = skillSuggestions,
            knowledgeSuggestions     = knowledgeSuggestions,
            onSkillQueryChanged      = onSkillQueryChanged,
            onKnowledgeQueryChanged  = onKnowledgeQueryChanged,
            attachments             = attachments,
            onRemoveAttachment      = onRemoveAttachment
        )
    }
}
// Toolbar composable
@Composable
private fun InputActionToolbar(
    isPlanModeActive:  Boolean,
    onPlanModeToggle:  () -> Unit,
    onOpenToolPicker:  () -> Unit,
    onOpenSkillPicker: () -> Unit,
    activeToolCount:   Int,
    activeSkillCount:  Int,
    isGenerating:      Boolean,
    onWebClick:        () -> Unit,
    onCodeClick:       () -> Unit,
    onCalcClick:       () -> Unit,
    // Attachment shortcuts
    onTakePhoto:       () -> Unit = {},
    onPickFile:        () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        // Plan Mode toggle
        PlanModeChip(isActive = isPlanModeActive, onClick = onPlanModeToggle)

        // Tool Picker
        ActionChip(
            label      = if (activeToolCount > 0) "Tools ($activeToolCount)" else "Tools",
            icon       = Icons.Outlined.Build,
            iconTint   = Color(0xFF06B6D4),
            isActive   = activeToolCount > 0,
            onClick    = onOpenToolPicker
        )

        // Skill Picker
        ActionChip(
            label      = if (activeSkillCount > 0) "Skills ($activeSkillCount)" else "Skills",
            icon       = Icons.Outlined.AutoAwesome,
            iconTint   = Color(0xFFEC4899),
            isActive   = activeSkillCount > 0,
            onClick    = onOpenSkillPicker
        )

        // Quick dynamic tools — each wired to its own callback
        QuickToolChip(label = "Web",  emoji = "", onClick = onWebClick)
        QuickToolChip(label = "Code", emoji = "", onClick = onCodeClick)
        QuickToolChip(label = "Calc", emoji = "", onClick = onCalcClick)

        // Divider
        Box(modifier = Modifier.width(1.dp).height(20.dp).background(DividerColor))

        // Attachment shortcuts
        ActionChip(
            label = "Camera",
            icon = Icons.Outlined.PhotoCamera,
            iconTint = AiriTheme.onSurfaceVariant,
            isActive = false,
            onClick = onTakePhoto
        )

        ActionChip(
            label = "File",
            icon = Icons.Outlined.AttachFile,
            iconTint = AiriTheme.onSurfaceVariant,
            isActive = false,
            onClick = onPickFile
        )
    }
}

@Composable
private fun PlanModeChip(isActive: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        if (isActive) CosmicAccent.copy(0.20f) else Color.White.copy(0.04f), tween(AIRIAnimations.FAST), label = "plan_bg"
    )
    val border by animateColorAsState(
        if (isActive) CosmicAccent.copy(0.60f) else DividerColor, tween(AIRIAnimations.FAST), label = "plan_border"
    )
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(AIRIShapes.xs)
            .background(bg)
            .border(1.dp, border, AIRIShapes.xs)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Text("", fontSize = 12.sp)
        Text(
            "Plan",
            fontSize    = 11.sp,
            fontWeight  = if (isActive) FontWeight.Bold else FontWeight.Medium,
            color       = if (isActive) CosmicAccent else AiriTheme.onSurfaceVariant
        )
        AnimatedVisibility(
            visible = isActive,
            enter   = fadeIn() + expandHorizontally(),
            exit    = fadeOut() + shrinkHorizontally()
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(CosmicAccent)
            )
        }
    }
}

@Composable
private fun ActionChip(
    label:    String,
    icon:     ImageVector,
    iconTint: Color,
    isActive: Boolean,
    onClick:  () -> Unit
) {
    val bg by animateColorAsState(
        if (isActive) iconTint.copy(0.15f) else Color.White.copy(0.04f), tween(AIRIAnimations.FAST), label = "chip_bg"
    )
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(AIRIShapes.xs)
            .background(bg)
            .border(
                1.dp,
                if (isActive) iconTint.copy(0.50f) else DividerColor,
                AIRIShapes.xs
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(icon, null, tint = if (isActive) iconTint else AiriTheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp))
        Text(
            label, fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            color = if (isActive) iconTint else AiriTheme.onSurfaceVariant
        )
    }
}

@Composable
private fun QuickToolChip(label: String, emoji: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .height(30.dp)
            .clip(AIRIShapes.xs)
            .background(Color.White.copy(0.03f))
            .border(0.5.dp, DividerColor, AIRIShapes.xs)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(emoji, fontSize = 11.sp)
        Text(label, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant, fontWeight = FontWeight.Medium)
    }
}
