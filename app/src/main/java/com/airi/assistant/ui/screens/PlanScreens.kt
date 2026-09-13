package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.monetization.PricingConfig
import com.airi.assistant.domain.monetization.currentPlanActionState
import com.airi.assistant.domain.monetization.PlanActionState
import com.airi.assistant.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreePlanScreen(onBack: () -> Unit, onOpenPro: () -> Unit) {
    PlanScaffold(title = stringResource(R.string.plan_free_title), onBack = onBack) {
        PlanHeader(Icons.Outlined.Lock, stringResource(R.string.plan_free_badge), SemanticWarn)
        Text(stringResource(R.string.plan_free_description), color = AiriTheme.onSurfaceVariant, fontSize = 15.sp)
        PlanCard {
            PlanItem(Icons.Outlined.Chat, stringResource(R.string.plan_free_chat), stringResource(R.string.plan_free_chat_desc), true)
            PlanItem(Icons.Outlined.AutoAwesome, stringResource(R.string.plan_free_agent), stringResource(R.string.plan_free_agent_desc), false)
            PlanItem(Icons.Outlined.Extension, stringResource(R.string.plan_free_skills), stringResource(R.string.plan_free_skills_desc), true)
            PlanItem(Icons.Outlined.Campaign, stringResource(R.string.plan_free_ads), stringResource(R.string.plan_free_ads_desc), false)
        }
        Text(stringResource(R.string.plan_free_reminder), color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
        Button(onClick = onOpenPro, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) {
            Text(stringResource(R.string.plan_upgrade_pro), fontWeight = FontWeight.Bold)
        }
        OutlinedButton(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(stringResource(R.string.plan_continue_free))
        }
        FrozenBillingNote()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProPlanScreen(onBack: () -> Unit) {
    val isPro = ServiceLocator.subscriptionManager.isPro()
    PlanScaffold(title = stringResource(R.string.plan_pro_title), onBack = onBack) {
        PlanHeader(Icons.Outlined.Verified, if (isPro) stringResource(R.string.plan_pro_active) else stringResource(R.string.plan_pro_preview), SemanticSuccess)
        Text(stringResource(R.string.plan_pro_description), color = AiriTheme.onSurfaceVariant, fontSize = 15.sp)
        PlanCard {
            PlanItem(Icons.Outlined.AllInclusive, stringResource(R.string.plan_pro_unlimited), stringResource(R.string.plan_pro_unlimited_desc), true)
            PlanItem(Icons.Outlined.AutoAwesome, stringResource(R.string.plan_pro_agent), stringResource(R.string.plan_pro_agent_desc), true)
            PlanItem(Icons.Outlined.Extension, stringResource(R.string.plan_pro_all_features), stringResource(R.string.plan_pro_all_features_desc), true)
            PlanItem(Icons.Outlined.Block, stringResource(R.string.plan_pro_no_ads), stringResource(R.string.plan_pro_no_ads_desc), true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PriceCard(stringResource(R.string.plan_monthly), "${PricingConfig.PRO_MONTHLY_PRICE_USD}")
            PriceCard(stringResource(R.string.plan_annual), "${PricingConfig.PRO_ANNUAL_PRICE_USD}")
        }
        Button(onClick = { }, enabled = false, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) {
            Text(stringResource(R.string.plan_subscribe_pro), fontWeight = FontWeight.Bold)
        }
        FrozenBillingNote()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanScaffold(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(containerColor = AiriTheme.background, topBar = {
        TopAppBar(title = { Text(title, fontWeight = FontWeight.SemiBold) }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
        }, colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable private fun PlanHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = color, modifier = Modifier.size(28.dp))
        Text(text, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun PlanCard(content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant), shape = AIRIShapes.lg, content = { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content) })
}

@Composable private fun PlanItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, desc: String, enabled: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (enabled) SemanticSuccess else AiriTheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
        Column { Text(title, fontWeight = FontWeight.SemiBold); Text(desc, color = AiriTheme.onSurfaceVariant, fontSize = 12.sp) }
    }
}

@Composable private fun PriceCard(label: String, price: String) {
    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(label, color = AiriTheme.onSurfaceVariant); Text("${'$'}$price", color = CosmicAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun FrozenBillingNote() {
    val frozen = currentPlanActionState() == PlanActionState.FROZEN_UNTIL_BILLING_CONFIGURED
    if (frozen) Text(stringResource(R.string.plan_billing_frozen), color = SemanticWarn, fontSize = 12.sp)
}
