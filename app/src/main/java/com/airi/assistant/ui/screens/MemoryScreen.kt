package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.R
import com.airi.assistant.memory.entity.ChatMessage  // Long id, non-nullable
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: ChatViewModel,
    onBack:    () -> Unit
) {
    val memoryMessages by viewModel.memoryEntries.collectAsState()
    val memoryCount    by viewModel.memoryCount.collectAsState()
    var showConfirm    by remember { mutableStateOf(false) }
    var searchQuery    by remember { mutableStateOf("") }
    var showSearch     by remember { mutableStateOf(false) }
    val snackbarHost   = remember { SnackbarHostState() }
    val listState      = rememberLazyListState()
    val scope          = rememberCoroutineScope()

    LaunchedEffect(Unit) { viewModel.loadMemoryEntries() }

    val displayMessages = remember(memoryMessages, searchQuery) {
        if (searchQuery.isBlank()) memoryMessages
        else memoryMessages.filter {
            it.content.contains(searchQuery, ignoreCase = true)
        }
    }

    // Clear confirmation
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor   = SurfaceFloating,
            shape            = AIRIShapes.xl,
            icon             = { Icon(Icons.Outlined.DeleteSweep, null, tint = SemanticError, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.memory_clear_confirm_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.memory_clear_dialog_body), color = AiriTheme.onSurfaceVariant, lineHeight = 20.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearMemory()
                        showConfirm = false
                        scope.launch { snackbarHost.showSnackbar("Memory cleared") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError),
                    shape  = AIRIShapes.md
                ) { Text(stringResource(R.string.memory_clear_button), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    Scaffold(
        containerColor = AiriTheme.background,
        snackbarHost   = { SnackbarHost(snackbarHost) },
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AiriTheme.background),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Outlined.ArrowBack, null, tint = AiriTheme.onBackground)
                        }
                    },
                    title = {
                        Column {
                            Text(stringResource(R.string.memory_title), fontWeight = FontWeight.Bold, color = AiriTheme.onBackground)
                            if (memoryCount > 0) {
                                Text(
                                    stringResource(R.string.memory_stored_count, memoryCount),
                                    fontSize = 11.sp,
                                    color    = CosmicAccent.copy(0.75f)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                            Icon(
                                if (showSearch) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                                null,
                                tint = if (showSearch) CosmicAccent else AiriTheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        if (memoryCount > 0) {
                            IconButton(onClick = { showConfirm = true }) {
                                Icon(Icons.Outlined.DeleteSweep, null, tint = SemanticError.copy(0.65f), modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                )
                // Inline search bar
                AnimatedVisibility(showSearch, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    SearchBar(
                        query    = searchQuery,
                        onChange = { searchQuery = it },
                        onClear  = { searchQuery = "" },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                HorizontalDivider(color = DividerColor)
            }
        }
    ) { padding ->
        if (memoryMessages.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val pulse = rememberInfiniteTransition(label = "mem_pulse")
                    val alpha by pulse.animateFloat(
                        0.12f, 0.28f,
                        infiniteRepeatable(tween(1800), RepeatMode.Reverse),
                        label = "mem_alpha"
                    )
                    Box(
                        modifier = Modifier.size(72.dp).clip(CircleShape)
                            .background(Brush.radialGradient(listOf(CosmicAccent.copy(alpha), Color.Transparent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.Psychology, null, tint = CosmicAccent.copy(0.55f), modifier = Modifier.size(32.dp))
                    }
                    Text(stringResource(R.string.memory_empty_title), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = AiriTheme.onBackground)
                    Text(stringResource(R.string.memory_empty_subtitle), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
        } else if (displayMessages.isEmpty() && searchQuery.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.SearchOff, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    Text("No memories match \"$searchQuery\"", fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    // Summary bar
                    Surface(
                        shape = AIRIShapes.md,
                        color = CosmicAccent.copy(0.08f),
                        border = BorderStroke(0.5.dp, CosmicAccent.copy(0.18f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Psychology, null, tint = CosmicAccent, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.episodic_memory_label),
                                fontSize = 12.sp,
                                color = AiriTheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Surface(shape = CircleShape, color = CosmicAccent.copy(0.18f)) {
                                Text(
                                    "${displayMessages.size}",
                                    fontSize = 11.sp,
                                    color = CosmicAccent,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }
                items(displayMessages, key = { it.id }) { msg ->
                    MemoryEntryCard(msg = msg, searchQuery = searchQuery)
                }
            }
        }
    }
}

@Composable
private fun MemoryEntryCard(msg: ChatMessage, searchQuery: String) {
    val isUser  = msg.role == "user"
    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

    Surface(
        shape  = AIRIShapes.md,
        color  = SurfaceRaised,
        border = BorderStroke(0.5.dp, DividerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .background(if (isUser) CosmicAccent.copy(0.20f) else SurfaceHighlight),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isUser) "U" else "A",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) CosmicAccent else AiriTheme.onSurfaceVariant
                    )
                }
                Text(
                    if (isUser) "You" else "AIRI",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) CosmicAccent else AiriTheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                Text(timeStr, fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(0.55f))
            }
            Text(
                msg.content,
                fontSize  = 13.sp,
                color     = AiriTheme.onBackground.copy(0.82f),
                maxLines  = 4,
                overflow  = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun SearchBar(
    query:    String,
    onChange: (String) -> Unit,
    onClear:  () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape  = AIRIShapes.md,
        color  = SurfaceRaised,
        border = BorderStroke(0.5.dp, if (query.isNotBlank()) CosmicAccent.copy(0.35f) else DividerColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Outlined.Search, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            androidx.compose.foundation.text.BasicTextField(
                value         = query,
                onValueChange = onChange,
                singleLine    = true,
                modifier      = Modifier.weight(1f),
                textStyle     = androidx.compose.ui.text.TextStyle(
                    color    = AiriTheme.onBackground,
                    fontSize = 13.sp
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(CosmicAccent),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text(stringResource(R.string.memory_search_hint), color = AiriTheme.outline, fontSize = 13.sp)
                    inner()
                }
            )
            if (query.isNotBlank()) {
                IconButton(onClick = onClear, modifier = Modifier.size(20.dp)) {
                    Icon(Icons.Outlined.Close, null, tint = AiriTheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}
