package com.airi.assistant.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.core.IntentRouter
import com.airi.assistant.core.UnifiedCognitiveLoop
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiriApp(onImportModel: () -> Unit, onStartAiri: () -> Unit) {

    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(DrawerValue.Closed)

    val llamaManager = remember { LlamaManager(context) }
    val intentRouter = remember { IntentRouter() }
    val cognitiveLoop = remember {
        UnifiedCognitiveLoop(context, intentRouter, llamaManager)
    }

    var showSettings by remember { mutableStateOf(false) }
    var showModelManager by remember { mutableStateOf(false) }

    val chatMessages = remember { mutableStateListOf<ChatMessage>() }
    var currentInput by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            Column(Modifier.fillMaxSize().padding(20.dp)) {

                IconButton(onClick = {
                    scope.launch { drawerState.close() }
                }) {
                    Icon(Icons.Rounded.ArrowForward, null)
                }

                Spacer(Modifier.height(20.dp))

                Text("AIRI")

                Spacer(Modifier.height(20.dp))

                Row(Modifier.clickable { showModelManager = true }) {
                    Icon(Icons.Rounded.Layers, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Models")
                }

                Spacer(Modifier.height(10.dp))

                Row(Modifier.clickable { showSettings = true }) {
                    Icon(Icons.Rounded.Settings, null)
                    Spacer(Modifier.width(10.dp))
                    Text("Settings")
                }
            }
        }
    ) {

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("AIRI Assistant") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Rounded.Menu, null)
                        }
                    }
                )
            }
        ) { paddingValues: PaddingValues ->

            Box(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    items(chatMessages) { msg ->
                        Text(msg.content)
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {

                    TextField(
                        value = currentInput,
                        onValueChange = { currentInput = it },
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(onClick = {
                        if (currentInput.isNotBlank() && !isGenerating) {

                            val userMsg = ChatMessage("user", currentInput)
                            val aiMsg = ChatMessage("assistant", "")

                            chatMessages.add(userMsg)
                            chatMessages.add(aiMsg)

                            val input = currentInput
                            currentInput = ""
                            isGenerating = true

                            scope.launch {
                                cognitiveLoop.processStream(
                                    input = input,
                                    onToken = { token ->
                                        val index = chatMessages.lastIndex
                                        if (index >= 0) {
                                            val updated = chatMessages[index].copy(
                                                content = chatMessages[index].content + token
                                            )
                                            chatMessages[index] = updated
                                        }
                                    },
                                    onComplete = {
                                        isGenerating = false
                                    }
                                )
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.Send, null)
                    }
                }
            }
        }
    }
}
