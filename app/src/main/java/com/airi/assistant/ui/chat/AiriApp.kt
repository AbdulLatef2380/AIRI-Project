package com.airi.assistant.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ai.LlamaManager
import com.airi.assistant.core.IntentRouter
import com.airi.assistant.core.UnifiedCognitiveLoop
import com.airi.assistant.memory.entity.ChatMessage
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiriApp(onImportModel: () -> Unit, onStartAiri: () -> Unit) {

    val context = LocalContext.current
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
            SidebarContent(
                onClose = { scope.launch { drawerState.close() } },
                onOpenSettings = { showSettings = true },
                onOpenModels = { showModelManager = true }
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Black,
            topBar = {
                TopAppBar(
                    title = {
                        Text("AIRI Assistant", color = Color.White)
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(Icons.Rounded.Menu, contentDescription = null, tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        ) { padding ->

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {

                if (chatMessages.isEmpty()) {
                    EmptyState(onImportModel)
                } else {
                    ChatList(chatMessages)
                }

                Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                    InputBar(
                        value = currentInput,
                        onValueChange = { currentInput = it },
                        onSend = {
                            if (currentInput.isNotBlank() && !isGenerating) {

                                val userMsg = ChatMessage("user", currentInput)
                                val aiMsg = ChatMessage("assistant", "")

                                chatMessages.add(userMsg)
                                chatMessages.add(aiMsg)

                                val input = currentInput
                                currentInput = ""
                                isGenerating = true

                                cognitiveLoop.processStream(
                                    input = input,
                                    onToken = { token ->
                                        val index = chatMessages.lastIndex
                                        val updated = chatMessages[index].copy(
                                            content = chatMessages[index].content + token
                                        )
                                        chatMessages[index] = updated
                                    },
                                    onComplete = {
                                        isGenerating = false
                                    }
                                )
                            }
                        }
                    )
                }

                if (showSettings) {
                    SettingsScreen { showSettings = false }
                }

                if (showModelManager) {
                    ModelManagerScreen(
                        onClose = { showModelManager = false },
                        onImport = onImportModel
                    )
                }
            }
        }
    }
}

@Composable
fun ChatList(messages: List<ChatMessage>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 100.dp),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(messages) { msg ->
            ChatBubble(msg)
        }
    }
}

@Composable
fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) AiriCyan.copy(0.1f) else AiriPanelBg)
                .border(1.dp, AiriCyan.copy(0.2f), RoundedCornerShape(16.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                color = if (isUser) AiriCyan else Color.White
            )
        }
    }
}

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(AiriPanelBg, RoundedCornerShape(30.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        IconButton(onClick = onSend) {
            Icon(Icons.Rounded.GraphicEq, null, tint = Color.White)
        }

        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text("اكتب...", color = Color.Gray)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent
            )
        )

        IconButton(onClick = onSend) {
            Icon(Icons.Rounded.Send, null, tint = AiriCyan)
        }
    }
}

@Composable
fun SidebarContent(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {

        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.ArrowForward, null, tint = Color.White)
        }

        Spacer(Modifier.height(20.dp))

        Text("AIRI", color = AiriCyan)

        Spacer(Modifier.height(20.dp))

        NavItem(Icons.Rounded.Layers, "النماذج", onOpenModels)
        NavItem(Icons.Rounded.Settings, "الإعدادات", onOpenSettings)
    }
}

@Composable
fun NavItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Icon(icon, null, tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White)
    }
}

@Composable
fun EmptyState(onImport: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    val animated = animateFloatAsState(progress)

    LaunchedEffect(Unit) {
        while (progress < 1f) {
            delay(50)
            progress += 0.01f
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AIRI", color = Color.White)
        Spacer(Modifier.height(20.dp))
        LinearProgressIndicator(progress = animated.value)
    }
}

@Composable
fun ModelManagerScreen(onClose: () -> Unit, onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Models", color = Color.White)
    }
}

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Text("Settings", color = Color.White)
    }
}
