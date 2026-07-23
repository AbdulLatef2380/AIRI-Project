package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.*

import com.airi.assistant.ui.theme.AiriTheme
import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.billing.BillingManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.experiment.ExperimentManager
import com.airi.assistant.domain.monetization.PaywallTriggerEngine
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaywallScreen(
    onBack: () -> Unit,
    onPurchaseSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    val billingManager = remember {
        BillingManager(context, ServiceLocator.subscriptionManager)
    }

    val billingState by billingManager.billingState.collectAsState()
    val productDetails by billingManager.productDetails.collectAsState()

    var isRestoring by remember { mutableStateOf(false) }
    val triggerReason      = PaywallTriggerEngine.lastTriggerReason
    val subscriptionManager = ServiceLocator.subscriptionManager
    val usagePercent       = remember { PaywallTriggerEngine.getUsagePercent(subscriptionManager) }
    val ctaText            = remember { ExperimentManager.getValue(ExperimentManager.PAYWALL_CTA) }
    val showUrgency        = remember { ExperimentManager.getBool(ExperimentManager.PAYWALL_URGENCY) }

    val contextMessage: String? = remember {
        PaywallTriggerEngine.getPaywallMessage(triggerReason)
    }

    val upsellLevel = remember { PaywallTriggerEngine.getUpsellLevel() }

    LaunchedEffect(Unit) {
        AnalyticsService.paywallView(triggerReason.source)
        AnalyticsService.paywallShown(triggerReason.source, upsellLevel.name.lowercase())
        billingManager.connect()
    }

    LaunchedEffect(billingState) {
        when (val state = billingState) {
            is BillingManager.BillingState.PurchaseSuccess -> {
                AnalyticsService.subscribed("premium_monthly")
                snackbarHost.showSnackbar("Premium activated! Enjoy unlimited access.")
                onPurchaseSuccess()
            }
            is BillingManager.BillingState.Error -> {
                snackbarHost.showSnackbar(state.message)
                isRestoring = false
            }
            is BillingManager.BillingState.PurchasePending -> {
                snackbarHost.showSnackbar(state.message)
                isRestoring = false
            }
            else -> isRestoring = false
        }
    }

    DisposableEffect(Unit) {
        onDispose { billingManager.destroy() }
    }

    val accentColor = CosmicAccent
    val goldColor   = Color(0xFFFFB300)

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background.copy(alpha = 0.8f)),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                title = {
                    Text(stringResource(R.string.upgrade_to_premium), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(AiriTheme.background, AiriTheme.surface, AiriTheme.background)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(accentColor.copy(alpha = 0.3f), Color.Transparent)
                            )
                        )
                        .border(2.dp, accentColor.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Star,
                        contentDescription = null,
                        tint   = goldColor,
                        modifier = Modifier.size(40.dp)
                    )
                }
                if (usagePercent >= 40) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Outlined.Bolt,
                                    contentDescription = null,
                                    tint     = goldColor,
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text       = "AI Power",
                                    fontSize   = 11.sp,
                                    color      = AiriTheme.onBackground.copy(alpha = 0.55f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Text(
                                text     = "${100 - usagePercent}% remaining",
                                fontSize = 11.sp,
                                color    = if (usagePercent >= 80) goldColor else AiriTheme.onSurface.copy(alpha = 0.45f)
                            )
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = (1f - usagePercent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = when {
                                usagePercent >= 80 -> goldColor
                                usagePercent >= 60 -> Color(0xFFFF7043)
                                else               -> accentColor
                            },
                            trackColor = AiriTheme.onSurface.copy(alpha = 0.1f)
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text       = "AIRI Premium",
                        fontSize   = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color      = AiriTheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text      = if (contextMessage != null)
                            contextMessage
                        else
                            "Unlock the full power of your on-device AI assistant",
                        fontSize  = 14.sp,
                        color     = AiriTheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AIRIShapes.xl)
                        .background(accentColor.copy(alpha = 0.12f))
                        .border(1.5.dp, accentColor.copy(alpha = 0.4f), AIRIShapes.xl)
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text       = "Monthly",
                                fontSize   = 12.sp,
                                color      = AiriTheme.onBackground.copy(alpha = 0.55f),
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 1.sp
                            )
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text       = "$4.99",
                                    fontSize   = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color      = AiriTheme.onBackground
                                )
                                Text(
                                    text     = "/month",
                                    fontSize = 14.sp,
                                    color    = AiriTheme.onBackground.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(bottom = 6.dp, start = 4.dp)
                                )
                            }
                        }
                        Surface(
                            shape = AIRIShapes.md,
                            color = accentColor.copy(alpha = 0.25f)
                        ) {
                            Text(
                                text       = "PREMIUM",
                                fontSize   = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color      = accentColor,
                                letterSpacing = 1.5.sp,
                                modifier   = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(AIRIShapes.xl)
                        .background(AiriTheme.outline)
                        .border(1.dp, AiriTheme.outline, AIRIShapes.xl)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text       = "What's included",
                        fontSize   = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color      = AiriTheme.onBackground.copy(alpha = 0.6f),
                        letterSpacing = 0.5.sp
                    )

                    BenefitRow(
                        icon        = Icons.Outlined.AllInclusive,
                        title       = "Unlimited Messages",
                        description = "Send as many messages as you need, every day"
                    )
                    BenefitRow(
                        icon        = Icons.Outlined.AutoAwesome,
                        title       = "Unlimited Agent Execution",
                        description = "Run AI agents without daily execution caps"
                    )
                    BenefitRow(
                        icon        = Icons.Outlined.AutoAwesome,
                        title       = "Advanced AI Features",
                        description = "Background agent, priority model access, and more"
                    )
                    BenefitRow(
                        icon        = Icons.Outlined.Lock,
                        title       = "Complete Privacy",
                        description = "All processing stays on your device, always"
                    )
                    BenefitRow(
                        icon        = Icons.Outlined.Bolt,
                        title       = "Priority Performance",
                        description = "Fastest response times with optimized inference"
                    )
                }
                Button(
                    onClick = {
                        AnalyticsService.upgradeClick()
                        AnalyticsService.paywallClicked(triggerReason.source)
                        if (activity != null) {
                            billingManager.launchPurchaseFlow(activity)
                        } else {
                            scope.launch {
                                snackbarHost.showSnackbar("Unable to open billing from this screen.")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape  = AIRIShapes.md,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor   = AiriTheme.onSurface
                    ),
                    enabled = billingState !is BillingManager.BillingState.Connecting
                ) {
                    if (billingState is BillingManager.BillingState.Connecting) {
                        CircularProgressIndicator(
                            color       = AiriTheme.onBackground,
                            strokeWidth = 2.dp,
                            modifier    = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(Icons.Outlined.Star, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text       = ctaText,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize   = 15.sp
                        )
                    }
                }
                if (showUrgency) {
                    Surface(
                        shape = AIRIShapes.xs,
                        color = accentColor.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Bolt,
                                contentDescription = null,
                                tint     = accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text      = "Limited time: unlock full AI power",
                                fontSize  = 12.sp,
                                color     = accentColor,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                TextButton(
                    onClick = {
                        isRestoring = true
                        scope.launch {
                            billingManager.restorePurchases()
                            if (billingState !is BillingManager.BillingState.PurchaseSuccess) {
                                snackbarHost.showSnackbar("No active subscription found to restore.")
                                isRestoring = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AnimatedVisibility(visible = isRestoring) {
                        CircularProgressIndicator(
                            color       = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f),
                            strokeWidth = 1.5.dp,
                            modifier    = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        text  = "Restore Purchases",
                        color = AiriTheme.onSurfaceVariant.copy(alpha = 0.45f),
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = "Subscription renews automatically at \$4.99/month. Cancel anytime in Google Play. " +
                           "Payment is charged to your Google account at confirmation of purchase.",
                    fontSize  = 10.sp,
                    color     = AiriTheme.outline.copy(alpha = 0.28f),
                    textAlign = TextAlign.Center,
                    lineHeight = 15.sp,
                    modifier  = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun BenefitRow(
    icon: ImageVector,
    title: String,
    description: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(AIRIShapes.sm)
                .background(CosmicAccent.copy(alpha = 0.15f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.3f), AIRIShapes.sm),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector     = icon,
                contentDescription = null,
                tint            = CosmicAccent,
                modifier        = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = title,
                fontSize   = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color      = AiriTheme.onBackground
            )
            Text(
                text    = description,
                fontSize = 12.sp,
                color   = AiriTheme.onBackground.copy(alpha = 0.45f),
                lineHeight = 17.sp
            )
        }
    }
}
