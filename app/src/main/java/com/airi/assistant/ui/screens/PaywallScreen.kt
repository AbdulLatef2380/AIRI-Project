package com.airi.assistant.ui.screens

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*

private data class PlanFeature(val icon: ImageVector, val text: String, val premium: Boolean = true)

private val FEATURES = listOf(
    PlanFeature(Icons.Outlined.AllInclusive,    "استخدام غير محدود للذكاء الاصطناعي"),
    PlanFeature(Icons.Outlined.Memory,          "جميع النماذج المحلية والسحابية"),
    PlanFeature(Icons.Outlined.SmartToy,        "عميل ذكي مستقل بلا قيود"),
    PlanFeature(Icons.Outlined.Psychology,      "ذاكرة طويلة الأمد ومتقدمة"),
    PlanFeature(Icons.Outlined.Extension,       "جميع المهارات والموصلات"),
    PlanFeature(Icons.Outlined.Support,         "دعم أولوية مباشر")
)

@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    onPurchaseSuccess: () -> Unit = {}
) {
    var selectedPlan by remember { mutableStateOf("yearly") }
    var purchasing   by remember { mutableStateOf(false) }

    val inf = rememberInfiniteTransition(label = "paywall_glow")
    val glowAlpha by inf.animateFloat(0.12f, 0.26f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "g")

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "الترقية إلى Premium", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero
            Box(contentAlignment = Alignment.Center) {
                Box(modifier = Modifier.size(160.dp)
                    .background(Brush.radialGradient(listOf(SemanticWarning.copy(glowAlpha), Color.Transparent))))
                Box(
                    modifier = Modifier.size(90.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(SemanticWarning.copy(0.3f), SemanticWarning.copy(0.08f))))
                        .border(2.dp, SemanticWarning.copy(0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Outlined.Star, contentDescription = null, tint = SemanticWarning, modifier = Modifier.size(44.dp))
                }
            }
            Text("AIRI Premium", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("اطلق الإمكانات الكاملة\nللذكاء الاصطناعي", color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)

            // Plan selector
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlanOption(
                    label = "شهري",
                    price = "$9.99",
                    period = "/ شهر",
                    selected = selectedPlan == "monthly",
                    badge = null,
                    onClick = { selectedPlan = "monthly" },
                    modifier = Modifier.weight(1f)
                )
                PlanOption(
                    label = "سنوي",
                    price = "$59.99",
                    period = "/ سنة",
                    selected = selectedPlan == "yearly",
                    badge = "وفر 50%",
                    onClick = { selectedPlan = "yearly" },
                    modifier = Modifier.weight(1f)
                )
            }

            // Features
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .background(Surface1).border(1.dp, BorderLight, RoundedCornerShape(18.dp))
                    .padding(16.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FEATURES.forEach { feature ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                                    .background(SemanticWarning.copy(0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Outlined.Check, contentDescription = null, tint = SemanticWarning, modifier = Modifier.size(16.dp))
                            }
                            Text(feature.text, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // CTA
            Button(
                onClick = { purchasing = true /* trigger billing */ },
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !purchasing,
                colors = ButtonDefaults.buttonColors(containerColor = SemanticWarning, contentColor = Color(0xFF1A1000))
            ) {
                if (purchasing) {
                    CircularProgressIndicator(color = Color(0xFF1A1000), modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (selectedPlan == "yearly") "ابدأ تجربة مجانية 7 أيام" else "اشترك الآن",
                        fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                }
            }

            TextButton(onClick = onBack, colors = ButtonDefaults.textButtonColors(contentColor = TextTertiary)) {
                Text("متابعة بدون Premium", fontSize = 13.sp)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PlanOption(
    label: String, price: String, period: String,
    selected: Boolean, badge: String?,
    onClick: () -> Unit, modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) SemanticWarning.copy(0.14f) else Surface1)
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) SemanticWarning else BorderLight,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (badge != null) {
                NeuralBadge(badge, SemanticWarning)
                Spacer(Modifier.height(6.dp))
            }
            Text(label, color = if (selected) SemanticWarning else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(price, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(period, color = TextSecondary, fontSize = 11.sp)
        }
    }
}
