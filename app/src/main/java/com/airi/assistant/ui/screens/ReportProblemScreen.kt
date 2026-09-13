package com.airi.assistant.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportProblemScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var description by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onBack,
        sheetState = sheetState,
        containerColor = AiriTheme.surface,
        contentColor = AiriTheme.onSurface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (sent) {
                Icon(Icons.Outlined.BugReport, contentDescription = null, tint = CosmicAccent, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                Text(stringResource(R.string.report_sent_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.report_sent_message), color = AiriTheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp), colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)) {
                    Text(stringResource(R.string.done))
                }
            } else {
                Text(stringResource(R.string.report_problem_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                Text(stringResource(R.string.report_problem_subtitle), color = AiriTheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(2000) },
                    label = { Text(stringResource(R.string.report_problem_field_label)) },
                    placeholder = { Text(stringResource(R.string.report_problem_hint)) },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("${description.length} / 2000", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                Button(
                    onClick = {
                        context.getSharedPreferences("airi_user_reports", android.content.Context.MODE_PRIVATE).edit()
                            .putString("last_report", description.trim()).putLong("last_report_at", System.currentTimeMillis()).apply()
                        sent = true
                    },
                    enabled = description.trim().length >= 8,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    contentPadding = PaddingValues(vertical = 13.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
                ) { Text(stringResource(R.string.report_send)) }
            }
        }
    }
}
