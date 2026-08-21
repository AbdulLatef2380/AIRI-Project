package com.airi.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    val agent = remember { DesktopAgent() }
    var messages by remember { mutableStateOf(agent.history()) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "AIRI Desktop"
    ) {
        AiriDesktopApp(
            messages = messages,
            onSubmit = { input ->
                val reply = agent.submit(input)
                if (reply != null) {
                    messages = agent.history()
                }
            },
            onClear = {
                agent.clearHistory()
                messages = emptyList()
            }
        )
    }
}

@Composable
private fun AiriDesktopApp(
    messages: List<DesktopMessage>,
    onSubmit: (String) -> Unit,
    onClear: () -> Unit
) {
    var input by remember { mutableStateOf("") }

    fun submitInput() {
        if (input.isBlank()) return
        onSubmit(input)
        input = ""
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7DD3FC),
            secondary = Color(0xFF67E8F9),
            surface = Color(0xFF0F172A),
            background = Color(0xFF020617)
        )
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AIRI Desktop",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Linux foundation · local deterministic response · AIRI Core",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = onClear, enabled = messages.isNotEmpty()) {
                        Text("Clear local history")
                    }
                }

                if (messages.isEmpty()) {
                    EmptyConversation(modifier = Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items = messages, key = { it.id }) { message ->
                            ConversationMessage(message)
                        }
                    }
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (
                                event.type == KeyEventType.KeyDown &&
                                event.key == Key.Enter &&
                                !event.isShiftPressed
                            ) {
                                submitInput()
                                true
                            } else {
                                false
                            }
                        },
                    label = { Text("Ask AIRI") },
                    placeholder = { Text("اكتب طلبك ثم اضغط Enter") },
                    minLines = 2,
                    maxLines = 5
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "Enter للإرسال · Shift+Enter لسطر جديد",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Button(onClick = ::submitInput, enabled = input.isNotBlank()) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversation(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "AIRI is ready on this desktop",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Submit a request to verify local response and persistence.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ConversationMessage(message: DesktopMessage) {
    val isAiri = message.speaker == DesktopSpeaker.AIRI
    val containerColor = if (isAiri) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isAiri) "AIRI" else "You",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
