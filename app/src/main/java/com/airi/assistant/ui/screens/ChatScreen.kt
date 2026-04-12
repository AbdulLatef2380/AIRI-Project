package com.airi.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.airi.assistant.ui.Screen
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.theme.MessageBubbleAI
import com.airi.assistant.ui.theme.MessageBubbleUser
import com.airi.assistant.ui.theme.OverlayBackground
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = viewModel(),
    onNavigate: (Screen) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var attachmentDialog by remember { mutableStateOf(false) }

    fun showPending(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    fun navigate(screen: Screen) {
        scope.launch {
            drawerState.close()
            onNavigate(screen)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AppDrawer(
                onNavigate = { navigate(it) },
                onLogout = onLogout
            )
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = Color.Transparent,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    TopBar(
                        onMenu = { scope.launch { drawerState.open() } },
                        onSettings = { onNavigate(Screen.SETTINGS) },
                        onAppInfo = { onNavigate(Screen.APP_INFO) }
                    )
                },
                bottomBar = {
                    InputBar(
                        onSend = { input -> viewModel.sendMessage(input) },
                        onAttachment = { attachmentDialog = true },
                        onTemplates = { onNavigate(Screen.TEMPLATES) },
                        onModelSettings = { onNavigate(Screen.MODEL_SETTINGS) }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    QuickActions(
                        onTemplates = { onNavigate(Screen.TEMPLATES) },
                        onModelSettings = { onNavigate(Screen.MODEL_SETTINGS) },
                        onHistory = { onNavigate(Screen.HISTORY) }
                    )
                    MessageList(
                        messages = viewModel.messages,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            AgentOverlay(
                state = viewModel.agentState,
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }
    }

    if (attachmentDialog) {
        AttachmentDialog(
            onDismiss = { attachmentDialog = false },
            onSelect = {
                attachmentDialog = false
                showPending("$it سيستخدم منتقي ملفات Android عند توصيله بالتنفيذ الفعلي.")
            }
        )
    }
}

@Composable
fun TopBar(
    onMenu: () -> Unit,
    onSettings: () -> Unit,
    onAppInfo: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMenu) {
                Icon(Icons.Default.Menu, contentDescription = "القائمة", tint = Color.White)
            }
            Column {
                Text("AIRI", color = CosmicAccent, fontWeight = FontWeight.Bold)
                Text("Agent Active", color = Color(0xFF66FF99), style = MaterialTheme.typography.labelSmall)
            }
        }

        Row {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = "الإعدادات", tint = Color.White)
            }
            IconButton(onClick = onAppInfo) {
                Icon(Icons.Default.Info, contentDescription = "معلومات التطبيق", tint = CosmicAccent)
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("ابدأ محادثة جديدة مع AIRI", color = Color.White, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                "يمكنك إرسال أمر، فتح القوالب، ضبط النموذج المحلي، أو إرفاق ملف من زر الإضافة.",
                color = Color.LightGray
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            reverseLayout = true,
            contentPadding = PaddingValues(12.dp)
        ) {
            items(messages.reversed()) { msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 6.dp)
                .background(
                    color = if (message.isUser) MessageBubbleUser else MessageBubbleAI,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(12.dp)
        ) {
            Text(
                text = message.text,
                color = Color.White
            )
        }
    }
}

@Composable
fun InputBar(
    onSend: (String) -> Unit,
    onAttachment: () -> Unit,
    onTemplates: () -> Unit,
    onModelSettings: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(InputBarBackground)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(onClick = onTemplates, label = { Text("القوالب") })
            AssistChip(onClick = onModelSettings, label = { Text("النموذج المحلي") })
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAttachment) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "إضافة ملف",
                    tint = CosmicAccent
                )
            }

            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("اكتب أمرك...") },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            IconButton(onClick = {
                if (text.isNotBlank()) {
                    onSend(text.trim())
                    text = ""
                }
            }) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "إرسال",
                    tint = CosmicAccent
                )
            }
        }
    }
}

@Composable
fun AgentOverlay(
    state: com.airi.assistant.ui.viewmodel.AgentState,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = state.isWorking,
        modifier = modifier.padding(top = 80.dp)
    ) {
        Box(
            modifier = Modifier
                .background(OverlayBackground, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text(
                text = "⚡ ${state.currentAction}",
                color = CosmicAccent
            )
        }
    }
}

@Composable
fun QuickActions(
    onTemplates: () -> Unit,
    onModelSettings: () -> Unit,
    onHistory: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ActionCard("عرض القوالب", "تحميل أو استخدام قالب", Modifier.weight(1f), onTemplates)
        ActionCard("إعداد النموذج", "اختيار نموذج LLaMA", Modifier.weight(1f), onModelSettings)
        ActionCard("المحادثات", "فتح السجل", Modifier.weight(1f), onHistory)
    }
}

@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 76.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
        contentPadding = PaddingValues(12.dp)
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(title, color = CosmicAccent, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun AppDrawer(
    onNavigate: (Screen) -> Unit,
    onLogout: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0A0E27),
        drawerContentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(304.dp)
                .padding(16.dp)
        ) {
            Text("AIRI", color = CosmicAccent, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("لوحة التحكم", color = Color.LightGray)
            Spacer(Modifier.height(24.dp))
            DrawerButton("محادثة جديدة") { onNavigate(Screen.CHAT) }
            DrawerButton("المحادثات") { onNavigate(Screen.HISTORY) }
            DrawerButton("القوالب") { onNavigate(Screen.TEMPLATES) }
            DrawerButton("النموذج المحلي") { onNavigate(Screen.MODEL_SETTINGS) }
            DrawerButton("الإعدادات") { onNavigate(Screen.SETTINGS) }
            DrawerButton("معلومات التطبيق") { onNavigate(Screen.APP_INFO) }
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("تسجيل الخروج")
            }
        }
    }
}

@Composable
fun DrawerButton(
    text: String,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Text(text, modifier = Modifier.fillMaxWidth(), color = Color.White)
    }
}

@Composable
fun AttachmentDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("إغلاق")
            }
        },
        title = { Text("إضافة محتوى") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("اختر نوع المحتوى الذي تريد إرفاقه بالمحادثة.")
                Button(onClick = { onSelect("ملف") }, modifier = Modifier.fillMaxWidth()) {
                    Text("إرفاق ملف")
                }
                Button(onClick = { onSelect("صورة") }, modifier = Modifier.fillMaxWidth()) {
                    Text("إرفاق صورة")
                }
                Button(onClick = { onSelect("فيديو") }, modifier = Modifier.fillMaxWidth()) {
                    Text("إرفاق فيديو")
                }
            }
        }
    )
}
