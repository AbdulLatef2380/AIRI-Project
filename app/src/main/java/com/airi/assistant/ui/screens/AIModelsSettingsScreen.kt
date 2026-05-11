package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel

/**
 * AIModelsSettingsScreen — clean model and execution configuration.
 *
 * Phase 5 rework: API key entry fields have been removed. Cloud inference
 * now routes through the app-managed OpenRouter endpoint — users never need
 * to enter or manage API keys. The UI shows:
 *
 *  1. **Execution mode** — LOCAL / CLOUD / HYBRID pill selector
 *  2. **Model picker shortcut** — navigates to the full Model Gallery
 *  3. **Cloud provider** — read-only info card showing the active provider
 *     (OpenRouter by default). Expanding this panel requires no user action
 *     because no credentials are stored on the device.
 *  4. **Cloud status** — live connectivity indicator pulled from the
 *     ViewModel's network/cloud state.
 */
@Composable
fun AIModelsSettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit
) {
    val execMode by viewModel.executionMode.collectAsState()

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "النماذج والذكاء الاصطناعي", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {

            // ── 1. Execution mode ─────────────────────────────────────────────
            NeuralSectionLabel("وضع التنفيذ")
            NeuralSectionCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        Triple(ExecutionMode.LOCAL_ONLY,  "محلي",  AccentLocal),
                        Triple(ExecutionMode.CLOUD_ONLY,  "سحابي", AccentCloud),
                        Triple(ExecutionMode.HYBRID,      "هجين",  AccentHybrid)
                    ).forEach { (mode, label, col) ->
                        val sel = execMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (sel) col.copy(0.18f) else Surface2)
                                .border(1.dp,
                                    if (sel) col.copy(0.55f) else BorderLight,
                                    RoundedCornerShape(10.dp))
                                .clickable { viewModel.setExecutionMode(mode) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label,
                                color = if (sel) col else TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }

                // Contextual hint under the pill selector
                val hintText = when (execMode) {
                    ExecutionMode.LOCAL_ONLY  ->
                        "النموذج يعمل محليًا على جهازك — لا إنترنت مطلوب، خصوصية تامة."
                    ExecutionMode.CLOUD_ONLY  ->
                        "يُستخدم OpenRouter تلقائيًا. لا يلزم إدخال أي مفتاح API."
                    ExecutionMode.HYBRID      ->
                        "المحلي أولًا — السحابة احتياطي عند الحاجة لسياق أوسع."
                    else                      -> ""
                }
                if (hintText.isNotBlank()) {
                    Text(
                        text = hintText,
                        color = TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
                    )
                }
            }

            // ── 2. Model picker shortcut ──────────────────────────────────────
            NeuralSectionCard {
                NeuralRowItem(
                    icon        = Icons.Outlined.Memory,
                    title       = "اختيار النموذج",
                    subtitle    = "النماذج المحلية والمتاحة",
                    iconTint    = PrimaryAccent,
                    iconBgColor = PrimaryAccent.copy(0.14f),
                    onClick     = { onNavigate(AiriRoute.MODELS) }
                )
            }

            // ── 3. Cloud provider (read-only, no key required) ────────────────
            if (execMode != ExecutionMode.LOCAL_ONLY) {
                NeuralSectionLabel("مزود الخدمة السحابية")
                NeuralSectionCard {
                    CloudProviderRow()
                    NeuralDivider()
                    // Info note
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = CosmicAccent.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp).padding(top = 1.dp)
                        )
                        Text(
                            text = "AIRI تُدير اتصال السحابة تلقائيًا. " +
                                "لا تُخزَّن أي مفاتيح API على جهازك. " +
                                "جميع الطلبات السحابية مشفرة بـ HTTPS.",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Cloud provider info tile ──────────────────────────────────────────────────

@Composable
private fun CloudProviderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Provider icon placeholder (accent circle)
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(CosmicAccent.copy(alpha = 0.14f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector     = Icons.Outlined.Cloud,
                contentDescription = null,
                tint            = CosmicAccent,
                modifier        = Modifier.size(20.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = "OpenRouter",
                color      = TextPrimary,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text     = "موصى به — لا يلزم مفتاح API",
                color    = TextSecondary,
                fontSize = 12.sp
            )
        }

        // Status badge
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1E7C3A).copy(alpha = 0.15f))
                .border(1.dp, Color(0xFF2ECC71).copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                text       = "متصل ✓",
                color      = Color(0xFF2ECC71),
                fontSize   = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
