package com.airi.assistant.ui.screens

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.theme.*
import com.airi.assistant.voice.VoicePreferencesStore
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePersonalizationScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    val scope    = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Load current settings
    val stored = remember { VoicePreferencesStore.load(context) }

    var pitch          by remember { mutableStateOf(stored.pitch) }
    var rate           by remember { mutableStateOf(stored.rate) }
    var selectedVoice  by remember { mutableStateOf(stored.voiceName) }
    var selectedPreset by remember { mutableStateOf(stored.preset) }
    var voiceEnabled   by remember { mutableStateOf(stored.voiceEnabled) }
    var hotwordEnabled by remember { mutableStateOf(stored.hotwordEnabled) }

    // Available system TTS voices
    val availableVoices = remember { mutableStateListOf<Voice>() }
    var ttsInstance by remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(Unit) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInstance = (ttsInstance ?: return@TextToSpeech).also { /* set below */ }
            }
        }
        ttsInstance = tts
        // Collect English voices
        val voices = runCatching {
            tts.voices?.filter {
                it.locale.language == Locale.ENGLISH.language && !it.isNetworkConnectionRequired
            }?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
        availableVoices.addAll(voices)
        onDispose { tts.shutdown() }
    }

    fun previewVoice() {
        ttsInstance?.let { tts ->
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            if (selectedVoice.isNotBlank()) {
                tts.voices?.firstOrNull { it.name == selectedVoice }?.let { tts.voice = it }
            }
            tts.speak("Hello, I am AIRI, your personal AI assistant.", TextToSpeech.QUEUE_FLUSH, null, "preview")
        }
    }

    fun saveSettings() {
        VoicePreferencesStore.save(
            context        = context,
            pitch          = pitch,
            rate           = rate,
            voiceName      = selectedVoice,
            preset         = selectedPreset,
            voiceEnabled   = voiceEnabled,
            hotwordEnabled = hotwordEnabled
        )
        scope.launch { snackbar.showSnackbar("Voice preferences saved") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.voice_personalization_title), color = AiriTheme.onBackground, fontWeight = FontWeight.SemiBold)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = AiriTheme.onBackground)
                    }
                },
                actions = {
                    TextButton(onClick = ::saveSettings) {
                        Text(stringResource(R.string.save), color = CosmicAccent, fontWeight = FontWeight.SemiBold)
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
            item {
                VoiceCard(title = "Personality Preset", icon = "◈") {
                    Text(
                        "Presets automatically set pitch and speed to match a voice personality.",
                        fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 17.sp,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(VoicePreferencesStore.PersonalityPreset.entries) { preset ->
                            val isSelected = preset == selectedPreset
                            val bgColor by animateColorAsState(
                                if (isSelected) CosmicAccent.copy(alpha = 0.20f) else MaterialTheme.colorScheme.outline,
                                tween(200), label = "preset_bg"
                            )
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .width(90.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(bgColor)
                                    .border(
                                        1.dp,
                                        if (isSelected) CosmicAccent.copy(0.6f) else MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        selectedPreset = preset
                                        pitch = preset.pitch
                                        rate  = preset.rate
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(preset.emoji, fontSize = 24.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    preset.label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) CosmicAccent else AiriTheme.onBackground,
                                    textAlign = TextAlign.Center
                                )
                                if (isSelected) {
                                    Spacer(Modifier.height(4.dp))
                                    Box(
                                        Modifier.size(6.dp).clip(CircleShape)
                                            .background(CosmicAccent)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        selectedPreset.description,
                        fontSize = 11.sp, color = CosmicAccent.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            item {
                VoiceCard(title = "Voice Pitch", icon = "🎵") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.voice_pitch_low), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                        Text(
                            "%.2fx".format(pitch), fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = CosmicAccent
                        )
                        Text(stringResource(R.string.voice_pitch_high), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                    Slider(
                        value         = pitch,
                        onValueChange = { pitch = it; selectedPreset = customPresetFor(selectedPreset) },
                        valueRange    = 0.5f..2.0f,
                        steps         = 29,
                        colors = SliderDefaults.colors(
                            thumbColor       = CosmicAccent,
                            activeTrackColor = CosmicAccent
                        )
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("0.5×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("1.0×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("2.0×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
            item {
                VoiceCard(title = "Speech Rate", icon = "⏱") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.voice_speed_slow), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                        Text(
                            "%.2fx".format(rate), fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, color = CosmicAccent
                        )
                        Text(stringResource(R.string.voice_speed_fast), fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
                    }
                    Slider(
                        value         = rate,
                        onValueChange = { rate = it; selectedPreset = customPresetFor(selectedPreset) },
                        valueRange    = 0.5f..2.0f,
                        steps         = 29,
                        colors = SliderDefaults.colors(
                            thumbColor       = CosmicAccent,
                            activeTrackColor = CosmicAccent
                        )
                    )
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("0.5×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("1.0×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Text("2.0×", fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
            }
            item {
                VoiceCard(title = "TTS Voice", icon = "🗣️") {
                    if (availableVoices.isEmpty()) {
                        Text(
                            "No offline English voices found. Install language packs in Android Settings → Text-to-Speech.",
                            fontSize = 12.sp, color = AiriTheme.onSurfaceVariant, lineHeight = 17.sp
                        )
                    } else {
                        // Default option
                        VoiceOption(
                            name       = "System Default",
                            locale     = "Auto",
                            isSelected = selectedVoice.isBlank(),
                            onClick    = { selectedVoice = "" }
                        )
                        Spacer(Modifier.height(4.dp))
                        availableVoices.take(8).forEach { voice ->
                            VoiceOption(
                                name       = voice.name.replace("#", " ").replace("-", " ").split(" ").take(3).joinToString(" "),
                                locale     = voice.locale.displayName,
                                isSelected = selectedVoice == voice.name,
                                onClick    = { selectedVoice = voice.name }
                            )
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            item {
                VoiceCard(title = "Voice Features", icon = "🎛️") {
                    ToggleRow(
                        label       = "Enable Voice Input & Output",
                        description = "Allow AIRI to listen and speak",
                        checked     = voiceEnabled,
                        onChecked   = { voiceEnabled = it }
                    )
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    ToggleRow(
                        label       = "Wake Word Detection",
                        description = "\"Hey AIRI\" always-on listening",
                        checked     = hotwordEnabled,
                        onChecked   = { hotwordEnabled = it },
                        enabled     = voiceEnabled
                    )
                }
            }
            item {
                Button(
                    onClick  = ::previewVoice,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccent),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.voice_preview_button), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick  = ::saveSettings,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = CosmicAccentDark),
                    shape    = RoundedCornerShape(14.dp)
                ) {
                    Text(stringResource(R.string.voice_save_preferences), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** Returns the same preset if pitch/rate still match, otherwise keeps current preset label */
private fun customPresetFor(current: VoicePreferencesStore.PersonalityPreset) = current

@Composable
private fun VoiceCard(title: String, icon: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape    = RoundedCornerShape(14.dp),
        color    = AiriTheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(icon, fontSize = 16.sp)
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun VoiceOption(name: String, locale: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) CosmicAccent.copy(0.12f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f))
            .border(0.5.dp, if (isSelected) CosmicAccent.copy(0.4f) else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
            Text(locale, fontSize = 11.sp, color = AiriTheme.onSurfaceVariant)
        }
        if (isSelected) {
            Icon(Icons.Filled.CheckCircle, null, tint = CosmicAccent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ToggleRow(
    label: String, description: String, checked: Boolean,
    onChecked: (Boolean) -> Unit, enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = AiriTheme.onBackground.copy(alpha = if (enabled) 1f else 0.4f))
            Text(description, fontSize = 12.sp, color = AiriTheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.4f))
        }
        Switch(
            checked = checked, onCheckedChange = onChecked, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = MaterialTheme.colorScheme.onSurface, checkedTrackColor = CosmicAccent)
        )
    }
}
