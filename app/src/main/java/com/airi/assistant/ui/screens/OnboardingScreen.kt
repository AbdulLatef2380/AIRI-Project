package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.airi.assistant.ui.components.NeuralAccentButton
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch

private data class OnboardPage(
    val icon: ImageVector,
    val iconColor: Color,
    val title: String,
    val description: String
)

private val PAGES = listOf(
    OnboardPage(Icons.Outlined.AutoAwesome, PrimaryAccent, "AIRI — ذكاء اصطناعي محلي", "مساعد ذكاء اصطناعي يعمل على جهازك بالكامل دون الحاجة إلى إرسال بياناتك إلى السحابة."),
    OnboardPage(Icons.Outlined.Psychology,  AccentCloud,   "نماذج قوية ومتعددة",        "اختر من بين نماذج LLM المتقدمة وشغّلها محلياً أو عبر API بمرونة كاملة."),
    OnboardPage(Icons.Outlined.SmartToy,    SemanticSuccess, "عميل ذكي مستقل",           "AIRI قادر على التخطيط وتنفيذ المهام المتعددة الخطوات نيابةً عنك."),
    OnboardPage(Icons.Outlined.Mic,         SecondaryAccent, "صوت ومحادثة حية",           "تحدث مع AIRI بلغتك الطبيعية مع دعم كامل للعربية والإنجليزية.")
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onSkip:     () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope      = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0)
    ) {
        // Ambient background glow
        Box(
            modifier = Modifier
                .size(400.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-80).dp)
                .background(
                    Brush.radialGradient(
                        listOf(PrimaryAccent.copy(0.12f), Color.Transparent)
                    )
                )
        )

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Skip button
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp).statusBarsPadding()) {
                TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.TopEnd)) {
                    Text("تخطي", color = TextSecondary, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.weight(0.5f))

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth()
            ) { page ->
                OnboardPageContent(PAGES[page])
            }

            Spacer(Modifier.weight(1f))

            // Dots indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                repeat(PAGES.size) { i ->
                    val selected = pagerState.currentPage == i
                    val width by animateDpAsState(if (selected) 24.dp else 8.dp, label = "dot_w")
                    Box(
                        modifier = Modifier
                            .height(8.dp)
                            .width(width)
                            .clip(CircleShape)
                            .background(if (selected) PrimaryAccent else BorderMid)
                    )
                }
            }

            // CTA
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 40.dp).navigationBarsPadding()) {
                if (pagerState.currentPage == PAGES.size - 1) {
                    NeuralAccentButton("ابدأ الآن", onClick = onComplete, icon = Icons.Outlined.ArrowForward)
                } else {
                    NeuralAccentButton(
                        text = "التالي",
                        onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                        icon = Icons.Outlined.ArrowForward
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardPageContent(page: OnboardPage) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        val inf = rememberInfiniteTransition(label = "onboard_pulse")
        val alpha by inf.animateFloat(0.18f, 0.38f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "alpha")
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(page.iconColor.copy(alpha), Color.Transparent)))
                .border(1.5.dp, page.iconColor.copy(0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(page.icon, contentDescription = null, tint = page.iconColor, modifier = Modifier.size(52.dp))
        }
        Text(page.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = (-0.4).sp)
        Text(page.description, color = TextSecondary, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
    }
}
