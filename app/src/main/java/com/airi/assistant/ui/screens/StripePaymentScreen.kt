@file:OptIn(ExperimentalMaterial3Api::class)
package com.airi.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.billing.CreditPackage
import com.airi.assistant.billing.StripeManager
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

/**
 * StripePaymentScreen — credit packs + subscription purchase UI.
 *
 * Tabs:
 *  - Credits  → buy one-time credit packs
 *  - Premium  → buy monthly / annual premium subscription
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StripePaymentScreen(
    stripeManager:       StripeManager,
    subscriptionManager: SubscriptionManager,
    onBack:              () -> Unit = {}
) {
    val context      = LocalContext.current
    val scope        = rememberCoroutineScope()
    val paymentState by stripeManager.paymentState.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Credits", "Premium")

    val isPremium = subscriptionManager.isPremium()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.payment_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor   = AiriTheme.background,
                contentColor     = CosmicAccent
            ) {
                tabs.forEachIndexed { i, t ->
                    Tab(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i; stripeManager.resetState() },
                        text     = { Text(t, color = if (selectedTab == i) CosmicAccent else AiriTheme.onSurfaceVariant) }
                    )
                }
            }
            AnimatedVisibility(visible = paymentState !is StripeManager.PaymentState.PaymentIdle && paymentState !is StripeManager.PaymentState.CheckoutReady) {
                PaymentStateBanner(paymentState) { stripeManager.resetState() }
            }

            when (selectedTab) {
                0 -> CreditsTab(stripeManager, paymentState, context, scope)
                1 -> PremiumTab(stripeManager, isPremium, paymentState, context, scope)
            }
        }
    }
}

@Composable
private fun CreditsTab(
    stripe:       StripeManager,
    paymentState: StripeManager.PaymentState,
    context:      android.content.Context,
    scope:        kotlinx.coroutines.CoroutineScope
) {
    var selectedPack by remember { mutableStateOf<CreditPackage?>(null) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                "Purchase Credits",
                fontSize    = 20.sp,
                fontWeight  = FontWeight.Bold,
                color       = AiriTheme.onBackground
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Credits supplement your daily quota. They never expire.",
                fontSize = 14.sp,
                color    = AiriTheme.onSurfaceVariant
            )
        }

        items(CreditPackage.entries) { pack ->
            CreditPackCard(
                pack       = pack,
                isSelected = selectedPack == pack,
                onClick    = { selectedPack = if (selectedPack == pack) null else pack }
            )
        }

        item {
            AnimatedVisibility(visible = selectedPack != null) {
                Button(
                    onClick = {
                        val pack = selectedPack ?: return@Button
                        scope.launch {
                            val url = stripe.purchaseCredits(pack)
                            if (url != null) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    enabled  = selectedPack != null && paymentState !is StripeManager.PaymentState.Processing,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = AIRIShapes.md,
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = CosmicAccent,
                        disabledContainerColor = AiriTheme.outline
                    )
                ) {
                    if (paymentState is StripeManager.PaymentState.Processing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = AiriTheme.onSurface)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Buy ${selectedPack?.displayName ?: ""} — ${selectedPack?.priceString ?: ""}", fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(8.dp))
            SecurityBadge()
        }
    }
}

@Composable
private fun PremiumTab(
    stripe:       StripeManager,
    isPremium:    Boolean,
    paymentState: StripeManager.PaymentState,
    context:      android.content.Context,
    scope:        kotlinx.coroutines.CoroutineScope
) {
    var annual by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier            = Modifier.fillMaxSize()
    ) {
        item {
            if (isPremium) {
                PremiumActiveBanner()
            } else {
                PremiumHeroCard()
            }
        }

        if (!isPremium) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.payment_monthly), color = if (!annual) AiriTheme.onBackground else AiriTheme.onSurfaceVariant, fontWeight = if (!annual) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked    = annual,
                        onCheckedChange = { annual = it },
                        colors     = SwitchDefaults.colors(checkedThumbColor = CosmicAccent, checkedTrackColor = CosmicAccent.copy(alpha = 0.3f))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.payment_annual), color = if (annual) AiriTheme.onBackground else AiriTheme.onSurfaceVariant, fontWeight = if (annual) FontWeight.SemiBold else FontWeight.Normal)
                    if (annual) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = SemanticSuccess) { Text(stringResource(R.string.payment_save_20), color = AiriTheme.onSurface, fontSize = 10.sp) }
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
                    shape  = AIRIShapes.md,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CosmicAccent.copy(0.3f))
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(if (annual) "$79.99" else "$9.99", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = CosmicAccent)
                            Text(if (annual) "/year" else "/month", color = AiriTheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
                        }
                        Divider(color = AiriTheme.outline)
                        val features = listOf("2,000 daily credits (10× free)", "Priority model access", "All connectors unlocked", "Community skill marketplace", "Developer API access", "Priority support")
                        features.forEach { f ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = SemanticSuccess)
                                Spacer(Modifier.width(8.dp))
                                Text(f, fontSize = 14.sp, color = AiriTheme.onBackground)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    val url = stripe.purchaseSubscription(annual)
                                    if (url != null) {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                }
                            },
                            enabled  = paymentState !is StripeManager.PaymentState.Processing,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            shape    = AIRIShapes.md,
                            colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                        ) {
                            Text(stringResource(R.string.payment_subscribe_now), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            item { SecurityBadge() }
        }
    }
}

@Composable
private fun CreditPackCard(pack: CreditPackage, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        onClick  = onClick,
        colors   = CardDefaults.cardColors(
            containerColor = if (isSelected) CosmicAccent.copy(alpha = 0.1f) else AiriTheme.surfaceVariant
        ),
        shape    = AIRIShapes.md,
        border   = if (isSelected) androidx.compose.foundation.BorderStroke(2.dp, CosmicAccent)
                   else if (pack.highlight != null) androidx.compose.foundation.BorderStroke(1.dp, SemanticSuccess.copy(0.5f))
                   else null
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pack.emoji, fontSize = 28.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pack.displayName, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                    pack.highlight?.let {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = SemanticSuccess) { Text(it, color = AiriTheme.onSurface, fontSize = 9.sp) }
                    }
                }
                Text("${pack.totalCredits.toLocaleStr()} credits", fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
                if (pack.bonusPercent > 0) {
                    Text(stringResource(R.string.payment_bonus_included, pack.bonusPercent), fontSize = 11.sp, color = SemanticSuccess)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(pack.priceString, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (isSelected) CosmicAccent else AiriTheme.onBackground)
                Text("${"%.1f".format(pack.costPer1kCents)}¢/1k", fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PremiumHeroCard() {
    Box(
        Modifier.fillMaxWidth().clip(AIRIShapes.xl)
            .background(Brush.linearGradient(listOf(CosmicAccent, CosmicAccentDark)))
            .padding(24.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.payment_airi_premium), fontWeight = FontWeight.Bold, fontSize = 22.sp, color = AiriTheme.onSurface)
            Text(stringResource(R.string.payment_premium_desc), color = AiriTheme.onSurface.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun PremiumActiveBanner() {
    Card(
        colors = CardDefaults.cardColors(containerColor = SemanticSuccess.copy(0.1f)),
        shape  = AIRIShapes.md,
        border = androidx.compose.foundation.BorderStroke(1.dp, SemanticSuccess.copy(0.3f))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Stars, null, Modifier.size(32.dp), tint = SemanticSuccess)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.payment_premium_active), fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                Text(stringResource(R.string.payment_premium_active_desc), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PaymentStateBanner(state: StripeManager.PaymentState, onDismiss: () -> Unit) {
    val (color, icon, text) = when (state) {
        is StripeManager.PaymentState.Success  -> Triple(SemanticSuccess, Icons.Default.CheckCircle, "Payment successful! Your account has been updated.")
        is StripeManager.PaymentState.Failed   -> Triple(SemanticError, Icons.Default.Error, state.message)
        is StripeManager.PaymentState.Pending  -> Triple(SemanticWarn, Icons.Default.HourglassEmpty, state.message)
        is StripeManager.PaymentState.Processing -> Triple(CosmicAccent, Icons.Default.Sync, "Processing payment…")
        else -> return
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(0.1f)),
        shape  = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Spacer(Modifier.width(8.dp))
            Text(text, Modifier.weight(1f), fontSize = 13.sp, color = AiriTheme.onBackground)
            IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Close, "Dismiss", Modifier.size(16.dp), tint = AiriTheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SecurityBadge() {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(12.dp), tint = AiriTheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.payment_stripe_footer), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

private fun Int.toLocaleStr(): String {
    return "%,d".format(this)
}
