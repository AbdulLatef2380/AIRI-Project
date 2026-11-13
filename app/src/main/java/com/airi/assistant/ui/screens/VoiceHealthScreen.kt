package com.airi.assistant.ui.screens

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.ui.theme.AIRIShapes
import com.airi.assistant.ui.theme.AiriTheme
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.voice.VoiceHealthTracker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceHealthScreen(onBack: () -> Unit) {
    val health by VoiceHealthTracker.snapshot.collectAsState()
    val context = LocalContext.current
    val battery = remember(context) { readBattery(context) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.voice_health_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    IconButton(onClick = VoiceHealthTracker::reset) {
                        Icon(Icons.Filled.Refresh, stringResource(R.string.voice_health_reset), tint = AiriTheme.onBackground)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = AiriTheme.background
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = AIRIShapes.md, color = CosmicAccent.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.voice_health_summary), modifier = Modifier.padding(16.dp), color = AiriTheme.onBackground, fontSize = 13.sp)
            }
            Surface(shape = AIRIShapes.md, color = AiriTheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    MetricRow(stringResource(R.string.voice_health_sessions), health.sessions.toString())
                    MetricRow(stringResource(R.string.voice_health_completed), health.completedSessions.toString())
                    MetricRow(stringResource(R.string.voice_health_wake_detections), health.wakeDetections.toString())
                    MetricRow(stringResource(R.string.voice_health_errors), health.errorCount.toString())
                    MetricRow(stringResource(R.string.voice_health_avg_latency), health.averageFirstAudioLatencyMs?.let { "$it ms" } ?: stringResource(R.string.voice_health_not_available))
                    MetricRow(stringResource(R.string.voice_health_last_latency), health.lastFirstAudioLatencyMs?.let { "$it ms" } ?: stringResource(R.string.voice_health_not_available))
                    MetricRow(stringResource(R.string.voice_health_last_error), health.lastErrorCode ?: stringResource(R.string.voice_health_not_available))
                    MetricRow(stringResource(R.string.voice_health_battery), battery)
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = VoiceHealthTracker::reset,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent)
            ) {
                Text(stringResource(R.string.voice_health_reset), color = AiriTheme.onSurface)
            }
        }
    }
}

private fun readBattery(context: Context): String {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return context.getString(R.string.voice_health_not_available)
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return context.getString(R.string.voice_health_not_available)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val percent = (level * 100 / scale).coerceIn(0, 100)
    return "$percent%${if (charging) " · charging" else ""}"
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp)
        Text(value, color = AiriTheme.onBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}
