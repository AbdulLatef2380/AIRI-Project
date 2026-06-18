package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.billing.BillingHistoryStore
import com.airi.assistant.billing.BillingRecord
import com.airi.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * BillingHistoryScreen — shows the complete billing history from [BillingHistoryStore].
 *
 * Displays:
 *  - Summary stats (total spent, total credits bought)
 *  - Filterable timeline of all billing events
 *  - Per-record detail: amount, status badge, Stripe payment ID
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillingHistoryScreen(
    billingHistoryStore: BillingHistoryStore,
    onBack:              () -> Unit = {}
) {
    val records by billingHistoryStore.historyFlow.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()) }

    val totalSpent    = billingHistoryStore.totalSpentCents()
    val totalCredits  = billingHistoryStore.totalCreditsBought()

    var filterStatus by remember { mutableStateOf<BillingRecord.Status?>(null) }
    val filtered     = remember(records, filterStatus) {
        if (filterStatus == null) records else records.filter { it.status == filterStatus }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Billing History", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding      = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Summary ──────────────────────────────────────────────────
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryCard(
                        label  = "Total Spent",
                        value  = "$${totalSpent / 100}.${"%02d".format(totalSpent % 100)}",
                        icon   = Icons.Default.AttachMoney,
                        color  = CosmicAccent,
                        modifier = Modifier.weight(1f)
                    )
                    SummaryCard(
                        label  = "Credits Bought",
                        value  = "%,d".format(totalCredits),
                        icon   = Icons.Default.Bolt,
                        color  = SemanticSuccess,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Filter chips ─────────────────────────────────────────────
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = filterStatus == null,
                        onClick  = { filterStatus = null },
                        label    = { Text("All") },
                        colors   = FilterChipDefaults.filterChipColors(selectedContainerColor = CosmicAccent.copy(0.15f))
                    )
                    BillingRecord.Status.entries.forEach { s ->
                        FilterChip(
                            selected = filterStatus == s,
                            onClick  = { filterStatus = if (filterStatus == s) null else s },
                            label    = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            colors   = FilterChipDefaults.filterChipColors(selectedContainerColor = statusColor(s).copy(0.15f))
                        )
                    }
                }
            }

            // ── Record list ──────────────────────────────────────────────
            if (filtered.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.ReceiptLong, null, Modifier.size(48.dp), tint = AiriTheme.onSurfaceVariant)
                            Text("No billing records", color = AiriTheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                items(filtered, key = { it.id }) { record ->
                    BillingRecordRow(record, dateFormat)
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(label: String, value: String, icon: ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape    = RoundedCornerShape(16.dp),
        modifier = modifier
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = color)
            Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = AiriTheme.onBackground)
            Text(label, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BillingRecordRow(record: BillingRecord, dateFormat: SimpleDateFormat) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AiriTheme.surfaceVariant),
        shape  = RoundedCornerShape(14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Icon ─────────────────────────────────────────────────────
            Box(
                Modifier.size(40.dp).background(recordColor(record.type).copy(0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(recordIcon(record.type), null, Modifier.size(20.dp), tint = recordColor(record.type))
            }

            // ── Content ──────────────────────────────────────────────────
            Column(Modifier.weight(1f)) {
                Text(record.description, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground, fontSize = 14.sp)
                Text(dateFormat.format(Date(record.timestampMs)), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                record.stripePaymentId?.let {
                    Text("ID: ${it.take(24)}…", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant)
                }
            }

            // ── Amount + status ───────────────────────────────────────────
            Column(horizontalAlignment = Alignment.End) {
                Text(record.amountString, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                if (record.credits > 0) {
                    Text("+${"%,d".format(record.credits)} cr", fontSize = 11.sp, color = SemanticSuccess)
                }
                Spacer(Modifier.height(2.dp))
                StatusBadge(record.status)
            }
        }
    }
}

@Composable
private fun StatusBadge(status: BillingRecord.Status) {
    val color = statusColor(status)
    Box(
        Modifier.background(color.copy(0.15f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            status.name.lowercase().replaceFirstChar { it.uppercase() },
            fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold
        )
    }
}

private fun statusColor(status: BillingRecord.Status) = when (status) {
    BillingRecord.Status.SUCCEEDED -> SemanticSuccess
    BillingRecord.Status.PENDING   -> SemanticWarn
    BillingRecord.Status.FAILED    -> SemanticError
    BillingRecord.Status.REFUNDED  -> Color(0xFF64B5F6)
}

private fun recordColor(type: BillingRecord.RecordType) = when (type) {
    BillingRecord.RecordType.CREDIT_PURCHASE       -> CosmicAccent
    BillingRecord.RecordType.SUBSCRIPTION_START    -> SemanticSuccess
    BillingRecord.RecordType.SUBSCRIPTION_RENEWAL  -> SemanticSuccess
    BillingRecord.RecordType.SUBSCRIPTION_CANCEL   -> SemanticError
    BillingRecord.RecordType.REFUND                -> Color(0xFF64B5F6)
}

private fun recordIcon(type: BillingRecord.RecordType) = when (type) {
    BillingRecord.RecordType.CREDIT_PURCHASE       -> Icons.Default.Bolt
    BillingRecord.RecordType.SUBSCRIPTION_START    -> Icons.Default.Stars
    BillingRecord.RecordType.SUBSCRIPTION_RENEWAL  -> Icons.Default.Autorenew
    BillingRecord.RecordType.SUBSCRIPTION_CANCEL   -> Icons.Default.Cancel
    BillingRecord.RecordType.REFUND                -> Icons.Default.Undo
}
