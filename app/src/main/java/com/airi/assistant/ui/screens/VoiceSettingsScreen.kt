package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*

@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs   = remember { context.getSharedPreferences("airi_voice_prefs", android.content.Context.MODE_PRIVATE) }

    var voskEnabled    by remember { mutableStateOf(prefs.getBoolean("vosk_enabled", true)) }
    var hotwordEnabled by remember { mutableStateOf(prefs.getBoolean("hotword_enabled", false)) }
    var vadEnabled     by remember { mutableStateOf(prefs.getBoolean("vad_enabled", true)) }
    var ttsSpeed       by remember { mutableStateOf(prefs.getFloat("tts_speed", 1.0f)) }
    var selectedSTT    by remember { mutableStateOf(prefs.getString("stt_engine", "VOSK") ?: "VOSK") }

    fun save() {
        prefs.edit()
            .putBoolean("vosk_enabled", voskEnabled)
            .putBoolean("hotword_enabled", hotwordEnabled)
            .putBoolean("vad_enabled", vadEnabled)
            .putFloat("tts_speed", ttsSpeed)
            .putString("stt_engine", selectedSTT)
            .apply()
    }

    Scaffold(
        containerColor = Surface0,
        topBar = { AiriScreenHeader(title = "إعدادات الصوت", onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // STT Engine
            NeuralSectionLabel("محرك التعرف على الكلام")
            NeuralSectionCard {
                listOf("VOSK" to "Vosk (محلي)", "WHISPER" to "Whisper (محلي)", "GOOGLE" to "Google STT (سحابي)").forEachIndexed { i, (id, label) ->
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { selectedSTT = id; save() }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Mic, contentDescription = null, tint = if (selectedSTT == id) PrimaryAccent else TextTertiary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        RadioButton(selected = selectedSTT == id, onClick = { selectedSTT = id; save() },
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryAccent, unselectedColor = TextTertiary))
                    }
                    if (i < 2) NeuralDivider()
                }
            }

            // Toggles
            NeuralSectionLabel("الميزات")
            NeuralSectionCard {
                NeuralRowItem(
                    icon = Icons.Outlined.Hearing,
                    title = "كشف نشاط الصوت (VAD)",
                    subtitle = "يكتشف متى تتوقف عن الكلام تلقائياً",
                    onClick = { vadEnabled = !vadEnabled; save() },
                    trailingContent = { NeuralToggle(checked = vadEnabled, onCheckedChange = { vadEnabled = it; save() }) },
                    showChevron = false
                )
                NeuralDivider()
                NeuralRowItem(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "كلمة التنبيه (Hotword)",
                    subtitle = "قل \"مرحباً Airi\" لتفعيل المساعد",
                    onClick = { hotwordEnabled = !hotwordEnabled; save() },
                    trailingContent = { NeuralToggle(checked = hotwordEnabled, onCheckedChange = { hotwordEnabled = it; save() }) },
                    showChevron = false
                )
            }

            // TTS Speed
            NeuralSectionLabel("سرعة الكلام")
            NeuralSectionCard {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("سرعة TTS", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        NeuralBadge("${String.format("%.1f", ttsSpeed)}×", PrimaryAccent)
                    }
                    Spacer(Modifier.height(8.dp))
                    Slider(
                        value = ttsSpeed,
                        onValueChange = { ttsSpeed = it },
                        onValueChangeFinished = { save() },
                        valueRange = 0.5f..2.0f,
                        steps = 5,
                        colors = SliderDefaults.colors(
                            thumbColor = PrimaryAccent,
                            activeTrackColor = PrimaryAccent,
                            inactiveTrackColor = Surface3
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0.5×", color = TextTertiary, fontSize = 11.sp)
                        Text("2.0×", color = TextTertiary, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
