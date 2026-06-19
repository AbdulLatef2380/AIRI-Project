package com.airi.assistant.ui.screens

import com.airi.assistant.ui.theme.AiriTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.terminal.TerminalRuntime
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.airi.assistant.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val runtime   = ServiceLocator.terminalRuntime
    val lines     by runtime.lines.collectAsStateWithLifecycle()
    val isRunning by runtime.isRunning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    var input     by remember { mutableStateOf("") }

    // Ensure session + auto-scroll
    LaunchedEffect(Unit) { runtime.ensureSession("Terminal") }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Terminal, null, tint = SemanticSuccess, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.terminal_title), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = AiriTheme.onBackground)
                        if (isRunning) {
                            Box(modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(SemanticSuccess.copy(0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                Text(stringResource(R.string.terminal_running_badge), fontSize = 10.sp, color = SemanticSuccess)
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, null, tint = AiriTheme.onBackground) } },
                actions = {
                    IconButton(onClick = { runtime.clearOutput() }) {
                        Icon(Icons.Outlined.CleaningServices, null, tint = Color.White.copy(0.6f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF060910))
            )
        },
        containerColor = Color(0xFF060910)
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Terminal output
            LazyColumn(
                state          = listState,
                modifier       = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                items(lines, key = { it.id }) { line ->
                    val color = when {
                        line.isInput -> Color(0xFF4FC3F7)
                        line.isError -> SemanticError.copy(0.9f)
                        else         -> Color.White.copy(0.78f)
                    }
                    Text(
                        text       = line.text,
                        color      = color,
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 17.sp,
                        modifier   = Modifier.fillMaxWidth()
                    )
                }
                if (isRunning) {
                    item(key = "spinner") {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            CircularProgressIndicator(color = SemanticSuccess, modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp)
                            Text(stringResource(R.string.terminal_running_status), fontSize = 11.sp, color = SemanticSuccess.copy(0.7f), fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }

            Divider(color = Color.White.copy(0.08f))

            // Command input bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF080B12))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("$", fontSize = 14.sp, color = SemanticSuccess, fontFamily = FontFamily.Monospace)
                TextField(
                    value         = input,
                    onValueChange = { input = it },
                    placeholder   = { Text(stringResource(R.string.terminal_command_placeholder), fontSize = 12.sp, color = Color.White.copy(0.25f), fontFamily = FontFamily.Monospace) },
                    singleLine    = true,
                    modifier      = Modifier.weight(1f).onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Enter -> { if (!isRunning && input.isNotBlank()) { scope.launch { runtime.execute(input); input = "" } }; true }
                                Key.DirectionUp   -> { runtime.historyUp()?.let { input = it }; true }
                                Key.DirectionDown -> { runtime.historyDown()?.let { input = it }; true }
                                else -> false
                            }
                        } else false
                    },
                    textStyle = LocalTextStyle.current.copy(
                        fontSize   = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color      = AiriTheme.onBackground
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor             = SemanticSuccess
                    )
                )
                IconButton(
                    onClick  = {
                        if (!isRunning && input.isNotBlank()) {
                            scope.launch { runtime.execute(input); input = "" }
                        }
                    },
                    enabled  = !isRunning && input.isNotBlank(),
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (!isRunning && input.isNotBlank()) SemanticSuccess.copy(0.2f) else Color.Transparent)
                ) {
                    Icon(Icons.Outlined.PlayArrow, null,
                        tint     = if (!isRunning && input.isNotBlank()) SemanticSuccess else Color.White.copy(0.2f),
                        modifier = Modifier.size(18.dp))
                }
            }

            // Bottom safe area
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
