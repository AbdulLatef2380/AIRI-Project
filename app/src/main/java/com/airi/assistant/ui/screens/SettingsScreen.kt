package com.airi.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.components.NeuralDivider
import com.airi.assistant.ui.components.AiriScreenHeader
import com.airi.assistant.ui.components.NeuralGlowDot
import com.airi.assistant.ui.components.NeuralRowItem
import com.airi.assistant.ui.components.NeuralSectionLabel
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel:  ChatViewModel,
    onBack:     () -> Unit,
    onNavigate: (String) -> Unit,
    onLogout:   () -> Unit
) {
    val user      = remember { FirebaseAuth.getInstance().currentUser }
    val email     = user?.email ?: "guest@airi.ai"
    val initial   = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    val isPremium = remember { viewModel.isPremium() }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(
                title = "الإعدادات",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // ── Profile card ──────────────────────────────────────────────
            ProfileHeroCard(
                email = email, initial = initial, isPremium = isPremium,
                onClick = { onNavigate(AiriRoute.PROFILE) }
            )

            Spacer(Modifier.height(8.dp))

            // ── PERSONALIZATION ───────────────────────────────────────────
            NeuralSectionLabel("التخصيص")
            SettingsGroup {
                NeuralRowItem(
                    icon = Icons.Outlined.Palette,
                    title = "التخصيص",
                    subtitle = "شخصية الذكاء الاصطناعي، الذاكرة، أسلوب الردود",
                    onClick = { onNavigate(AiriRoute.SETTINGS_CUSTOMIZATION) }
                )
            }

            // ── AI & RUNTIME ──────────────────────────────────────────────
            NeuralSectionLabel("الذكاء الاصطناعي والتشغيل")
            SettingsGroup {
                NeuralRowItem(
                    icon = Icons.Outlined.Psychology,
                    title = "النماذج والذكاء الاصطناعي",
                    subtitle = "مفاتيح API، وضع التنفيذ، المهارات",
                    onClick = { onNavigate(AiriRoute.SETTINGS_AI_MODELS) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Mic,
                    title = "الصوت",
                    subtitle = "التعرف على الكلام، كلمة التنبيه",
                    iconTint = SecondaryAccent,
                    iconBgColor = SecondaryAccent.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.VOICE_SETTINGS) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.MenuBook,
                    title = "قاعدة المعرفة",
                    subtitle = "نصوص وروابط ومستندات لسياق AIRI",
                    iconTint = SemanticSuccess,
                    iconBgColor = SemanticSuccess.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.KNOWLEDGE) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.SmartToy,
                    title = "العميل الذكي",
                    subtitle = "الأتمتة في الخلفية",
                    iconTint = SemanticWarning,
                    iconBgColor = SemanticWarning.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.AGENT_CONTROL) }
                )
            }

            // ── SYSTEM ────────────────────────────────────────────────────
            NeuralSectionLabel("النظام")
            SettingsGroup {
                NeuralRowItem(
                    icon = Icons.Outlined.Tune,
                    title = "الإعدادات العامة",
                    subtitle = "اللغة، المساعد الافتراضي",
                    onClick = { onNavigate(AiriRoute.SETTINGS_GENERAL) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Shield,
                    title = "الخصوصية والبيانات",
                    subtitle = "تصدير، استيراد، ضوابط البيانات",
                    iconTint = AccentCloud,
                    iconBgColor = AccentCloud.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.SETTINGS_PRIVACY) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Speed,
                    title = "الأداء",
                    subtitle = "معلومات الجهاز، التشخيصات",
                    onClick = { onNavigate(AiriRoute.PERFORMANCE) }
                )
            }

            // ── ACCOUNT ───────────────────────────────────────────────────
            NeuralSectionLabel("الحساب")
            SettingsGroup {
                NeuralRowItem(
                    icon = Icons.Outlined.Star,
                    title = "الاشتراك",
                    subtitle = if (isPremium) "Premium · نشط" else "المستوى المجاني · الترقية متاحة",
                    iconTint = SemanticWarning,
                    iconBgColor = SemanticWarning.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.PAYWALL) }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Info,
                    title = "حول AIRI",
                    subtitle = "معلومات التطبيق، شروط الاستخدام",
                    iconTint = TextTertiary,
                    iconBgColor = TextTertiary.copy(alpha = 0.14f),
                    onClick = { onNavigate(AiriRoute.SETTINGS_ABOUT) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Logout ────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SemanticError.copy(alpha = 0.08f))
                    .border(1.dp, SemanticError.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
                    .clickable { onLogout() }
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Logout, contentDescription = null, tint = SemanticError, modifier = Modifier.size(18.dp))
                    Text("تسجيل الخروج", color = SemanticError, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProfileHeroCard(
    email: String,
    initial: String,
    isPremium: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(Surface2, Surface1)
                )
            )
            .border(1.dp, BorderLight, RoundedCornerShape(20.dp))
            .drawBehind {
                drawIntoCanvas { canvas ->
                    val paint = Paint().apply {
                        asFrameworkPaint().setShadowLayer(
                            24f, 0f, 8f,
                            PrimaryAccent.copy(alpha = 0.12f).toArgb()
                        )
                    }
                    canvas.drawRoundRect(0f, 0f, size.width, size.height, 20.dp.toPx(), 20.dp.toPx(), paint)
                }
            }
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(listOf(PrimaryAccent.copy(0.3f), AccentDark.copy(0.15f)))
                    )
                    .border(1.5.dp, PrimaryAccent.copy(alpha = 0.55f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(initial, color = PrimaryAccent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("AIRI Agent", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(email, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuralGlowDot(color = if (isPremium) SemanticWarning else PrimaryAccent, size = 7.dp)
                    Text(
                        text = if (isPremium) "Premium" else "Free",
                        color = if (isPremium) SemanticWarning else PrimaryAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
    ) { content() }
}

// ── Settings surface containers used by sub-screens ──────────────────────────

@Composable
fun SettingsSurface(content: @Composable ColumnScope.() -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
        color = Surface1,
        tonalElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderLight)
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SettingsCategoryHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(icon, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(18.dp))
        Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SettingsActionRow(label: String, sublabel: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            if (!sublabel.isNullOrBlank()) {
                Text(sublabel, color = TextSecondary, fontSize = 12.sp)
            }
        }
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

// Keep legacy composables referenced by existing screens
@Composable
fun SettingsHubProfileCard(
    email: String,
    initial: String,
    isPremium: Boolean,
    onClick: () -> Unit
) = ProfileHeroCard(email, initial, isPremium, onClick)

@Composable
fun SettingsHubSectionLabel(text: String) = NeuralSectionLabel(text)

@Composable
fun SettingsHubCard(content: @Composable ColumnScope.() -> Unit) = SettingsGroup(content)

@Composable
fun SettingsHubRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) = NeuralRowItem(icon = icon, title = title, subtitle = subtitle, onClick = onClick)

@Composable
fun SettingsHubDivider() = NeuralDivider()

// AboutScreen referenced in nav (Arabic / settings-hub version)
@Composable
fun SettingsAboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "حول AIRI", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier.size(80.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(PrimaryAccent.copy(0.3f), Surface1))),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("A", color = PrimaryAccent, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("AIRI", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("نظام تشغيل الذكاء الاصطناعي", color = TextSecondary, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            NeuralSectionLabel("معلومات")
            SettingsGroup {
                NeuralRowItem(icon = Icons.Outlined.Info, title = "الإصدار", subtitle = "1.0.0", showChevron = false)
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Policy,
                    title = "سياسة الخصوصية",
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://airi.ai/privacy")))
                    }
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.Description,
                    title = "شروط الاستخدام",
                    onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://airi.ai/terms")))
                    }
                )
            }
        }
    }
}
