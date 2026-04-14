package com.airi.assistant.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.AiriRoute
import com.airi.assistant.ui.theme.CosmicAccent
import com.airi.assistant.ui.theme.InputBarBackground
import com.airi.assistant.ui.viewmodel.AgentState
import com.airi.assistant.ui.viewmodel.ChatMessage
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.ui.viewmodel.ModelUiState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigate: (String) -> Unit = {},
    onLogout: () -> Unit = {}
) {
    val drawerState   = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope         = rememberCoroutineScope()
    val messages      by viewModel.messages.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val agentState    by viewModel.agentState.collectAsState()
    val modelState    by viewModel.modelState.collectAsState()
    val snackbarHost  = remember { SnackbarHostState() }

    var showMenu            by remember { mutableStateOf(false) }
    var showAttachSheet     by remember { mutableStateOf(false) }
    var showGenSettings     by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch { snackbarHost.showSnackbar("File selected — attachment support coming soon") }
        }
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch { snackbarHost.showSnackbar("Image selected — vision support coming soon") }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            AiriDrawer(
                modelState = modelState,
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    onNavigate(route)
                },
                onNewChat = {
                    viewModel.clearMessages()
                    scope.launch { drawerState.close() }
                },
                onLogout = {
                    scope.launch { drawerState.close() }
                    onLogout()
                }
            )
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbarHost) },
            topBar = {
                ChatTopBar(
                    modelState  = modelState,
                    agentState  = agentState,
                    showMenu    = showMenu,
                    onMenuOpen  = { scope.launch { drawerState.open() } },
                    onNewChat   = { viewModel.clearMessages() },
                    onToggleDropdown = { showMenu = !showMenu },
                    onDismissDropdown = { showMenu = false },
                    onGenSettings = { showMenu = false; showGenSettings = true },
                    onSwitchModel = { showMenu = false; onNavigate(AiriRoute.MODELS) },
                    onExportChat  = {
                        showMenu = false
                        scope.launch { snackbarHost.showSnackbar("Export coming soon") }
                    }
                )
            },
            bottomBar = {
                ChatInputBar(
                    modelState      = modelState,
                    isGenerating    = agentState.isWorking,
                    onSend          = { text -> viewModel.sendMessage(text) },
                    onAttachClick   = { showAttachSheet = true },
                    onMicClick      = {
                        scope.launch { snackbarHost.showSnackbar("Voice input — Vosk integration coming soon") }
                    },
                    onOpenModels    = { onNavigate(AiriRoute.MODELS) }
                )
            }
        ) { padding ->
            ChatMessageList(
                messages      = messages,
                streamingText = streamingText,
                isGenerating  = agentState.isWorking,
                isModelReady  = modelState.isModelReady,
                modifier      = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        }
    }

    // ── Attach bottom sheet ──────────────────────────────────────────────────
    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            containerColor   = Color(0xFF12162E),
            contentColor     = Color.White,
            shape            = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Attach", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                Spacer(Modifier.height(4.dp))
                AttachOption(icon = Icons.Outlined.Image, label = "Pick image") {
                    showAttachSheet = false
                    imagePicker.launch("image/*")
                }
                AttachOption(icon = Icons.Outlined.AttachFile, label = "Pick file") {
                    showAttachSheet = false
                    filePicker.launch("*/*")
                }
                AttachOption(icon = Icons.Outlined.CameraAlt, label = "Camera (coming soon)", enabled = false) {}
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Generation settings dialog ───────────────────────────────────────────
    if (showGenSettings) {
        GenerationSettingsDialog(
            viewModel = viewModel,
            onDismiss = { showGenSettings = false }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// TOP BAR
// ─────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    modelState: ModelUiState,
    agentState: AgentState,
    showMenu: Boolean,
    onMenuOpen: () -> Unit,
    onNewChat: () -> Unit,
    onToggleDropdown: () -> Unit,
    onDismissDropdown: () -> Unit,
    onGenSettings: () -> Unit,
    onSwitchModel: () -> Unit,
    onExportChat: () -> Unit
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black.copy(alpha = 0.65f)
        ),
        navigationIcon = {
            IconButton(onClick = onMenuOpen) {
                Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
            }
        },
        title = {
            Column {
                Text(
                    "AIRI Agent",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 16.sp
                )
                Text(
                    text = when {
                        agentState.isWorking         -> "Generating…"
                        modelState.isModelReady      -> modelState.selectedModelName
                        modelState.isModelLoading    -> "Loading model…"
                        else                         -> "No model active"
                    },
                    fontSize = 11.sp,
                    color = when {
                        agentState.isWorking      -> CosmicAccent
                        modelState.isModelReady   -> CosmicAccent.copy(alpha = 0.85f)
                        else                      -> Color.White.copy(alpha = 0.45f)
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Outlined.AddComment, contentDescription = "New chat", tint = Color.White.copy(alpha = 0.8f))
            }
            Box {
                IconButton(onClick = onToggleDropdown) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White.copy(alpha = 0.8f))
                }
                DropdownMenu(
                    expanded  = showMenu,
                    onDismissRequest = onDismissDropdown,
                    containerColor   = Color(0xFF1A1F3A)
                ) {
                    DropdownMenuItem(
                        text  = { Text("Generation Settings", color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, tint = CosmicAccent) },
                        onClick = onGenSettings
                    )
                    DropdownMenuItem(
                        text  = { Text("Switch Model", color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = CosmicAccent) },
                        onClick = onSwitchModel
                    )
                    Divider(color = Color.White.copy(alpha = 0.1f))
                    DropdownMenuItem(
                        text  = { Text("Export Chat", color = Color.White) },
                        leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = Color.White.copy(alpha = 0.6f)) },
                        onClick = onExportChat
                    )
                }
            }
        }
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// MESSAGES
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String,
    isGenerating: Boolean,
    isModelReady: Boolean = false,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    LaunchedEffect(messages.size, streamingText.length) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    if (messages.isEmpty() && streamingText.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.SmartToy,
                    contentDescription = null,
                    tint = CosmicAccent.copy(alpha = 0.35f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "AIRI is ready",
                    color = Color.White.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isModelReady) "Ask anything — your model is active"
                    else "Activate a model from the Model Gallery first",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 13.sp
                )
            }
        }
    } else {
        LazyColumn(
            state            = listState,
            modifier         = modifier,
            reverseLayout    = true,
            contentPadding   = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (streamingText.isNotEmpty() && isGenerating) {
                item(key = "streaming") { AiStreamingBubble(text = streamingText) }
            }
            items(messages.reversed(), key = { "${it.hashCode()}_${it.isUser}" }) { msg ->
                if (msg.isUser) UserBubble(msg.text) else AiBubble(msg.text)
            }
        }
    }
}

@Composable
fun UserBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .background(
                    Brush.linearGradient(
                        listOf(CosmicAccent.copy(alpha = 0.25f), CosmicAccent.copy(alpha = 0.12f))
                    )
                )
                .border(1.dp, CosmicAccent.copy(alpha = 0.35f), RoundedCornerShape(18.dp, 4.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
fun AiBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.15f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.09f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 21.sp)
        }
    }
}

@Composable
fun AiStreamingBubble(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(CosmicAccent.copy(alpha = 0.2f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = CosmicAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, CosmicAccent.copy(alpha = 0.25f), RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 21.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color    = CosmicAccent.copy(alpha = 0.7f),
                trackColor = Color.White.copy(alpha = 0.08f)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// INPUT BAR
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun ChatInputBar(
    modelState: ModelUiState,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onAttachClick: () -> Unit,
    onMicClick: () -> Unit,
    onOpenModels: () -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    val canSend = text.isNotBlank() && modelState.isModelReady && !isGenerating

    Surface(
        color = InputBarBackground,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {

            // Model status chip
            if (!modelState.isModelReady && !modelState.isModelLoading) {
                TextButton(onClick = onOpenModels, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Icon(Icons.Outlined.Warning, contentDescription = null, tint = Color(0xFFFFCC00), modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("No model active — tap to select", color = Color(0xFFFFCC00), fontSize = 12.sp)
                }
            } else if (modelState.isModelLoading) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = CosmicAccent)
                    Spacer(Modifier.width(6.dp))
                    Text("Loading ${modelState.selectedModelName}…", color = CosmicAccent.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom
            ) {
                // + Attach
                IconButton(
                    onClick  = onAttachClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Attach", tint = Color.White.copy(alpha = 0.6f))
                }

                // TextField
                TextField(
                    value         = text,
                    onValueChange = { text = it },
                    modifier      = Modifier.weight(1f),
                    enabled       = modelState.isModelReady && !isGenerating,
                    placeholder   = {
                        Text(
                            when {
                                isGenerating              -> "Generating…"
                                modelState.isModelReady   -> "Message AIRI…"
                                else                      -> "Activate a model first…"
                            },
                            color = Color.White.copy(alpha = 0.3f),
                            fontSize = 14.sp
                        )
                    },
                    minLines = 1,
                    maxLines = 6,
                    shape    = RoundedCornerShape(20.dp),
                    colors   = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.White.copy(alpha = 0.08f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                        disabledContainerColor  = Color.White.copy(alpha = 0.03f),
                        focusedTextColor        = Color.White,
                        unfocusedTextColor      = Color.White,
                        disabledTextColor       = Color.White.copy(alpha = 0.3f),
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor  = Color.Transparent,
                        cursorColor             = CosmicAccent
                    )
                )

                // Mic
                IconButton(
                    onClick  = onMicClick,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Outlined.Mic,
                        contentDescription = "Voice input",
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                }

                // Send
                IconButton(
                    onClick  = { if (canSend) { onSend(text); text = "" } },
                    enabled  = canSend,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) CosmicAccent.copy(alpha = 0.2f)
                            else Color.Transparent
                        )
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (canSend) CosmicAccent else Color.White.copy(alpha = 0.2f)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DRAWER
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun AiriDrawer(
    modelState: ModelUiState,
    onNavigate: (String) -> Unit,
    onNewChat: () -> Unit,
    onLogout: () -> Unit
) {
    val user  = remember { FirebaseAuth.getInstance().currentUser }
    val email = user?.email ?: "guest@airi.ai"
    val initial = email.firstOrNull()?.uppercaseChar()?.toString() ?: "A"

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0D1124),
        drawerContentColor   = Color.White,
        modifier = Modifier.width(300.dp)
    ) {
        // ── Header ────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(CosmicAccent.copy(alpha = 0.15f), Color.Transparent)
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(CosmicAccent.copy(alpha = 0.2f))
                            .border(1.5.dp, CosmicAccent.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(initial, color = CosmicAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("AIRI Agent", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                        Text(email, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (modelState.isModelReady) CosmicAccent.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.07f)
                ) {
                    Text(
                        text = when {
                            modelState.isModelReady   -> "● ${modelState.selectedModelName}"
                            modelState.isModelLoading -> "○ Loading…"
                            else                      -> "○ No model"
                        },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        color = if (modelState.isModelReady) CosmicAccent else Color.White.copy(alpha = 0.4f)
                    )
                }
            }
        }

        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(8.dp))

        // ── Actions ───────────────────────────────────────────────────────
        DrawerActionItem(icon = Icons.Outlined.AddComment,  label = "New Chat",      onClick = onNewChat)
        DrawerNavItem(icon = Icons.Outlined.Forum,          label = "Chats",          route = AiriRoute.CHAT,         onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.SmartToy,       label = "Model Gallery",  route = AiriRoute.MODELS,       onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Psychology,     label = "Memory",         route = AiriRoute.MEMORY,       onNavigate = onNavigate)
        DrawerNavItem(icon = Icons.Outlined.Extension,      label = "Integrations",   route = AiriRoute.INTEGRATIONS, onNavigate = onNavigate)

        Spacer(Modifier.height(4.dp))
        Divider(color = Color.White.copy(alpha = 0.06f))
        Spacer(Modifier.height(4.dp))

        DrawerNavItem(icon = Icons.Outlined.Settings,       label = "Settings",       route = AiriRoute.SETTINGS,     onNavigate = onNavigate)

        Spacer(Modifier.weight(1f))
        Divider(color = Color.White.copy(alpha = 0.06f))

        DrawerActionItem(
            icon    = Icons.Outlined.Logout,
            label   = "Sign Out",
            tint    = Color(0xFFFF6B6B),
            onClick = onLogout
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun DrawerNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    route: String,
    onNavigate: (String) -> Unit
) {
    NavigationDrawerItem(
        icon   = { Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.7f)) },
        label  = { Text(label, color = Color.White) },
        selected = false,
        onClick = { onNavigate(route) },
        colors  = NavigationDrawerItemDefaults.colors(
            unselectedContainerColor = Color.Transparent,
            selectedContainerColor   = CosmicAccent.copy(alpha = 0.12f)
        ),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

@Composable
private fun DrawerActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White.copy(alpha = 0.7f),
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        icon     = { Icon(icon, contentDescription = null, tint = tint) },
        label    = { Text(label, color = tint) },
        selected = false,
        onClick  = onClick,
        colors   = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// ATTACH OPTION
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick  = onClick,
        enabled  = enabled,
        shape    = RoundedCornerShape(14.dp),
        color    = Color.White.copy(alpha = if (enabled) 0.07f else 0.03f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) CosmicAccent else Color.White.copy(alpha = 0.3f), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
            Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f), fontSize = 15.sp)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// GENERATION SETTINGS DIALOG
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun GenerationSettingsDialog(
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val temperature  by viewModel.temperature.collectAsState()
    val maxTokens    by viewModel.maxTokens.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()

    AlertDialog(
        onDismissRequest  = onDismiss,
        containerColor    = Color(0xFF12162E),
        titleContentColor = Color.White,
        textContentColor  = Color.White,
        shape             = RoundedCornerShape(20.dp),
        title = { Text("Generation Settings", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("These settings will apply to the next conversation.", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)

                // Temperature
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Temperature", fontSize = 13.sp)
                        Text("%.1f".format(temperature), color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = temperature,
                        onValueChange = { viewModel.setTemperature(it) },
                        valueRange = 0.1f..2.0f,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent)
                    )
                    Text("Lower = focused, Higher = creative", color = Color.White.copy(alpha = 0.35f), fontSize = 11.sp)
                }

                // Max tokens
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Max Tokens", fontSize = 13.sp)
                        Text("$maxTokens", color = CosmicAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                    Slider(
                        value = maxTokens.toFloat(),
                        onValueChange = { viewModel.setMaxTokens(it.toInt()) },
                        valueRange = 64f..2048f,
                        steps = 15,
                        colors = SliderDefaults.colors(thumbColor = CosmicAccent, activeTrackColor = CosmicAccent)
                    )
                }

                // System prompt
                Column {
                    Text("System Prompt Override", fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = systemPrompt,
                        onValueChange = { viewModel.setSystemPrompt(it) },
                        placeholder = { Text("Leave empty to use default", color = Color.White.copy(alpha = 0.3f), fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = CosmicAccent,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                            focusedTextColor     = Color.White,
                            unfocusedTextColor   = Color.White
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors  = ButtonDefaults.buttonColors(containerColor = CosmicAccent, contentColor = Color.Black)
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.6f)) }
        }
    )
}
