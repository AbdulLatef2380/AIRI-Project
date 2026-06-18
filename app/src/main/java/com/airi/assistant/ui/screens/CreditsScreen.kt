package com.airi.assistant.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.monetization.ActionType
import com.airi.assistant.domain.monetization.MeterSnapshot
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.accounting.TokenAccountant
import com.airi.assistant.ui.theme.*
import kotlin.math.min

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val creditEngine   = remember { ServiceLocator.creditMeteringEngine }
    val tokenAcct      = remember { ServiceLocator.tokenAccountant }

    var snapshot by remember { mutableStateOf(creditEngine.snapshot()) }
    val tokenStats by tokenAcct.stats.collectAsState()

    fun refresh() { snapshot = creditEngine.snapshot() }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Credits & Usage", color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = ::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        snackbarHost   = { SnackbarHost(snackbar) },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {

            // ── Daily Credit Balance ─────────────────────────────────────────
            item {
                CreditBalanceCard(snapshot = snapshot)
            }

            // ── Usage Alerts ─────────────────────────────────────────────────
            item {
                UsageAlertsCard(snapshot = snapshot)
            }

            // ── Per-Action Breakdown ─────────────────────────────────────────
            item {
                CreditsCard(title = "Today's Usage by Action", icon = Icons.Outlined.BarChart) {
                    if (snapshot.perActionDay.isEmpty()) {
                        Text(
                            "No actions recorded today yet.",
                            fontSize = 13.sp, color = AiriTheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        val maxValue = snapshot.perActionDay.values.maxOrNull()?.toFloat() ?: 1f
                        snapshot.perActionDay.entries
                            .sortedByDescending { it.value }
                            .forEach { (action, credits) ->
                                ActionUsageRow(
                                    action   = action,
                                    credits  = credits,
                                    maxValue = maxValue
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                    }
                }
            }

            // ── Token Accounting ─────────────────────────────────────────────
            item {
                CreditsCard(title = "Token Usage Today", icon = Icons.Outlined.Token) {
                    val activeProviders = tokenStats.filter { it.value.totalTokens > 0 }
                    if (activeProviders.isEmpty()) {
                        Text(
                            "No cloud API calls made today.",
                            fontSize = 13.sp, color = AiriTheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        activeProviders.forEach { (provider, stats) ->
                            TokenProviderRow(provider = provider, stats = stats)
                            Spacer(Modifier.height(8.dp))
                        }
                        Divider(color = DividerColor, modifier = Modifier.padding(vertical = 4.dp))
                        val totalTokens = tokenStats.values.sumOf { it.totalTokens }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Total today", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = AiriTheme.onBackground)
                            Text(
                                formatTokens(totalTokens),
                                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = CosmicAccent
                            )
                        }
                    }
                }
            }

            // ── Lifetime Stats ───────────────────────────────────────────────
            item {
                CreditsCard(title = "Lifetime Statistics", icon = Icons.Outlined.Timeline) {
                    StatRow("Total credits consumed", snapshot.lifetimeTotal.toString())
                    Divider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                    StatRow(
                        "Subscription tier",
                        if (snapshot.budget > 500) "Premium ✓" else "Free"
                    )
                    Divider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("Daily budget", "${snapshot.budget} credits")
                    Divider(color = DividerColor, modifier = Modifier.padding(vertical = 8.dp))
                    StatRow("Credits remaining today", "${snapshot.remaining}")
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun CreditBalanceCard(snapshot: MeterSnapshot) {
    val animatedFraction by animateFloatAsState(
        targetValue = min(snapshot.usedFraction, 1f),
        animationSpec = tween(1000),
        label = "credit_arc"
    )
    val ringColor = when {
        snapshot.usedFraction >= 0.90f -> SemanticError
        snapshot.usedFraction >= 0.75f -> SemanticWarn
        else                            -> CosmicAccent
    }

    Surface(
        shape    = RoundedCornerShape(18.dp),
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            // Circular progress indicator
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                    // Background ring
                    drawArc(
                        color       = Color.White.copy(alpha = 0.06f),
                        startAngle  = -220f,
                        sweepAngle  = 260f,
                        useCenter   = false,
                        style       = stroke
                    )
                    // Progress ring
                    drawArc(
                        color       = ringColor,
                        startAngle  = -220f,
                        sweepAngle  = 260f * animatedFraction,
                        useCenter   = false,
                        style       = stroke
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${snapshot.remaining}",
                        fontSize = 28.sp, fontWeight = FontWeight.ExtraBold,
                        color = ringColor
                    )
                    Text("remaining", fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "${snapshot.dailyTotal} / ${snapshot.budget} credits used today",
                fontSize = 14.sp, color = AiriTheme.onBackground, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Resets at midnight UTC",
                fontSize = 11.sp, color = AiriTheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UsageAlertsCard(snapshot: MeterSnapshot) {
    val alertLevel = when {
        snapshot.usedFraction >= 1.0f  -> "exhausted"
        snapshot.usedFraction >= 0.90f -> "critical"
        snapshot.usedFraction >= 0.75f -> "warning"
        else                            -> "ok"
    }
    val (bgColor, borderColor, icon, title, message) = when (alertLevel) {
        "exhausted" -> listOf(
            SemanticError.copy(0.10f), SemanticError.copy(0.30f),
            "🚫", "Daily limit reached",
            "All ${snapshot.budget} credits have been used. Upgrade to Premium for 10× more credits, or wait until midnight UTC."
        )
        "critical" -> listOf(
            SemanticError.copy(0.07f), SemanticError.copy(0.20f),
            "⚠️", "Almost out of credits",
            "${snapshot.remaining} credits remain. Consider upgrading to Premium to avoid interruptions."
        )
        "warning" -> listOf(
            SemanticWarn.copy(0.07f), SemanticWarn.copy(0.20f),
            "⚡", "75% of daily credits used",
            "${snapshot.remaining} credits remain today. Usage is on track."
        )
        else -> listOf(
            SemanticSuccess.copy(0.07f), SemanticSuccess.copy(0.20f),
            "✅", "Credits healthy",
            "${snapshot.remaining} of ${snapshot.budget} credits remain. You're well within your daily limit."
        )
    }

    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = bgColor as Color,
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor as Color, RoundedCornerShape(14.dp))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(icon as String, fontSize = 20.sp)
            Column {
                Text(title as String, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = AiriTheme.onBackground)
                Text(message as String, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant,
                    lineHeight = 17.sp)
            }
        }
    }
}

@Composable
private fun ActionUsageRow(action: ActionType, credits: Int, maxValue: Float) {
    val animatedWidth by animateFloatAsState(
        targetValue = credits / maxValue,
        animationSpec = tween(800),
        label = "action_bar"
    )
    val actionInfo = actionDisplayInfo(action)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(actionInfo.first, fontSize = 16.sp, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(actionInfo.second, fontSize = 12.sp, color = AiriTheme.onBackground)
                Text("$credits cr", fontSize = 12.sp, color = CosmicAccent, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(0.06f))
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(animatedWidth).fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp)).background(CosmicAccent)
                )
            }
        }
    }
}

@Composable
private fun TokenProviderRow(provider: CloudProvider, stats: TokenAccountant.ProviderStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(provider.name.lowercase().replaceFirstChar { it.uppercase() },
                fontSize = 13.sp, color = AiriTheme.onBackground, fontWeight = FontWeight.Medium)
            Text(
                "${stats.promptTokens}p + ${stats.completionTokens}c · ${stats.requestCount} calls",
                fontSize = 11.sp, color = AiriTheme.onSurfaceVariant
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatTokens(stats.totalTokens), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                color = CosmicAccent)
            val cost = stats.estimatedCostUsd(provider)
            if (cost > 0.0) {
                Text("~${"$%.4f".format(cost)}", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CreditsCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = AiriTheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
    }
}

private fun actionDisplayInfo(action: ActionType): Pair<String, String> = when (action) {
    ActionType.MESSAGE          -> "💬" to "Messages"
    ActionType.AGENT_EXECUTION  -> "🤖" to "Agent execution"
    ActionType.SKILL_USE        -> "✨" to "Skill use"
    ActionType.IMAGE_GENERATION -> "🖼" to "Image generation"
    ActionType.DOCUMENT_PROCESS -> "📄" to "Document processing"
    ActionType.BROWSER_FETCH    -> "🌐" to "Web fetch"
    ActionType.SCHEDULED_JOB    -> "⏰" to "Scheduled jobs"
    ActionType.RAG_RETRIEVAL    -> "🧠" to "Memory retrieval"
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000L -> "${"%.1f".format(n / 1_000_000.0)}M"
    n >= 1_000L     -> "${"%.1f".format(n / 1_000.0)}K"
    else             -> n.toString()
}
