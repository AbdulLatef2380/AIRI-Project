package com.airi.assistant.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airi.assistant.R
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.terminal.TerminalRuntime
import com.airi.assistant.ui.theme.*
import kotlinx.coroutines.launch

// ── Terminal colour tokens ───────────────────────────────────────────────────
private val TermBg         = Color(0xFF090C18)
private val TermSurface    = Color(0xFF0F1220)
private val TermBorder     = Color(0xFF1E2338)
private val TermInput      = Color(0xFF4FC3F7)   // user input — cyan
private val TermOutput     = Color(0xFFCDD5E0)   // output — off-white
private val TermError      = Color(0xFFFF6B6B)   // errors — red
private val TermWarn       = Color(0xFFFFD54F)   // warnings — amber
private val TermSuccess    = Color(0xFF4CAF50)   // ok status — green
private val TermPrompt     = Color(0xFF6B5CE7)   // prompt $ — accent violet
private val TermComment    = Color(0xFF546E7A)   // greyed-out comments
private val TermKeyword    = Color(0xFFB39DDB)   // keywords — light violet
private val TermString     = Color(0xFF80CBC4)   // string literals — teal
private val TermNumber     = Color(0xFFFFCC80)   // numbers — amber

// Shell keywords for syntax colouring
private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "do", "done",
    "case", "esac", "in", "function", "return", "exit", "echo", "export",
    "source", "alias", "unset", "set", "read", "true", "false", "null"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val runtime   = ServiceLocator.terminalRuntime
    val lines     by runtime.lines.collectAsStateWithLifecycle()
    val isRunning by runtime.isRunning.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()
    val context   = LocalContext.current

    var input          by remember { mutableStateOf("") }
    var historyIndex   by remember { mutableIntStateOf(-1) }
    var showHistory    by remember { mutableStateOf(false) }
    var showSearch     by remember { mutableStateOf(false) }
    var searchQuery    by remember { mutableStateOf("") }
    val inputFocus     = remember { FocusRequester() }
    val snackbar       = remember { SnackbarHostState() }

    // Command history from runtime
    val history by runtime.commandHistory.collectAsStateWithLifecycle()

    // Filtered lines for search
    val displayLines: List<TerminalRuntime.TerminalLine> = remember(lines, searchQuery) {
        if (searchQuery.isBlank()) lines
        else lines.filter { it.text.contains(searchQuery, ignoreCase = true) }
    }

    LaunchedEffect(Unit) {
        runtime.ensureSession("Terminal")
        inputFocus.requestFocus()
    }
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && !showSearch)
            listState.animateScrollToItem(lines.size - 1)
    }

    fun submitCommand() {
        val cmd = input.trim()
        if (cmd.isNotBlank()) {
            scope.launch { runtime.execute(cmd) }
            historyIndex = -1
        }
        input = ""
    }

    fun navigateHistory(up: Boolean) {
        if (history.isEmpty()) return
        historyIndex = if (up) {
            (historyIndex + 1).coerceAtMost(history.lastIndex)
        } else {
            (historyIndex - 1).coerceAtLeast(-1)
        }
        input = if (historyIndex >= 0) history[historyIndex] else ""
    }

    Scaffold(
        containerColor = TermBg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(color = TermSurface, shadowElevation = 0.dp) {
                Column {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = TermSurface),
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.Outlined.ArrowBack, null, tint = TermOutput.copy(0.7f))
                            }
                        },
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape)
                                        .background(if (isRunning) TermSuccess else TermComment)
                                )
                                Text(
                                    stringResource(R.string.terminal_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    color = TermOutput
                                )
                                if (isRunning) {
                                    Surface(
                                        shape = AIRIShapes.xs.copy(topStart = 4.dp),
                                        color = TermSuccess.copy(0.15f)
                                    ) {
                                        Text(
                                            stringResource(R.string.terminal_running_badge),
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = TermSuccess,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                                Icon(
                                    if (showSearch) Icons.Outlined.SearchOff else Icons.Outlined.Search,
                                    stringResource(if (showSearch) R.string.terminal_close_search_cd else R.string.terminal_search_cd),
                                    tint = if (showSearch) CosmicAccent else TermOutput.copy(0.55f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { showHistory = !showHistory }) {
                                Icon(
                                    Icons.Outlined.History,
                                    stringResource(R.string.terminal_history_cd),
                                    tint = if (showHistory) CosmicAccent else TermOutput.copy(0.55f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = {
                                val text = lines.joinToString("\n") { it.text }
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
                                scope.launch { snackbar.showSnackbar(context.getString(R.string.terminal_copied)) }
                            }) {
                                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.terminal_copy_cd), tint = TermOutput.copy(0.55f), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { runtime.clearOutput() }) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.terminal_clear_cd), tint = TermOutput.copy(0.55f), modifier = Modifier.size(20.dp))
                            }
                        }
                    )
                    // Search bar
                    AnimatedVisibility(showSearch, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(TermSurface).padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Search, null, tint = TermComment, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            BasicTextField2Compat(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = stringResource(R.string.terminal_search_hint),
                                modifier = Modifier.weight(1f)
                            )
                            if (searchQuery.isNotBlank()) {
                                Text("${displayLines.size} matches", fontSize = 10.sp, color = TermComment, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(8.dp))
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Outlined.Close, null, tint = TermComment, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    // History drawer
                    AnimatedVisibility(showHistory, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        if (history.isEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().background(TermSurface).padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Outlined.History, null, tint = TermComment, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.terminal_history_empty), fontSize = 12.sp, color = TermComment, fontFamily = FontFamily.Monospace)
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().background(TermSurface).heightIn(max = 160.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(history.take(20)) { cmd ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().clip(AIRIShapes.xs)
                                            .clickable {
                                                input = cmd
                                                showHistory = false
                                                inputFocus.requestFocus()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("$", fontSize = 11.sp, color = TermPrompt, fontFamily = FontFamily.Monospace)
                                        Text(cmd, fontSize = 12.sp, color = TermOutput.copy(0.75f), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                        Icon(Icons.Outlined.NorthWest, null, tint = TermComment, modifier = Modifier.size(12.dp))
                                    }
                                }
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(TermBorder))
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).background(TermBg)) {
            // Output area
            if (displayLines.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.Terminal, null, tint = TermComment, modifier = Modifier.size(32.dp))
                        Text(
                            if (showSearch && searchQuery.isNotBlank()) "No matches for \"$searchQuery\""
                            else stringResource(R.string.terminal_no_output),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TermComment
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(displayLines, key = { it.id }) { line ->
                        TerminalLineRow(line = line, searchQuery = searchQuery)
                    }
                    if (isRunning) {
                        item(key = "running_indicator") {
                            Row(
                                modifier = Modifier.padding(top = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    color = TermSuccess,
                                    modifier = Modifier.size(9.dp),
                                    strokeWidth = 1.5.dp
                                )
                                Text(
                                    stringResource(R.string.terminal_running_ellipsis),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TermSuccess.copy(0.65f)
                                )
                            }
                        }
                    }
                }
            }

            // Input bar
            Box(modifier = Modifier.fillMaxWidth().background(TermSurface)) {
                Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(TermBorder))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Prompt
                    Text(
                        if (isRunning) "…" else "$",
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) TermWarn else TermPrompt,
                        modifier = Modifier.padding(end = 10.dp)
                    )

                    // Text field (using BasicTextField for terminal feel)
                    androidx.compose.foundation.text.BasicTextField(
                        value = input,
                        onValueChange = { input = it; historyIndex = -1 },
                        enabled = !isRunning,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(inputFocus)
                            .onKeyEvent { event ->
                                when {
                                    event.type == KeyEventType.KeyDown && event.key == Key.Enter -> {
                                        submitCommand(); true
                                    }
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp -> {
                                        navigateHistory(up = true); true
                                    }
                                    event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown -> {
                                        navigateHistory(up = false); true
                                    }
                                    else -> false
                                }
                            },
                        textStyle = TextStyle(
                            color = TermInput,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        cursorBrush = SolidColor(CosmicAccent),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submitCommand() }),
                        decorationBox = { inner ->
                            if (input.isEmpty()) {
                                Text(
                                    stringResource(R.string.terminal_command_placeholder),
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TermComment
                                )
                            }
                            inner()
                        }
                    )

                    // History navigation arrows
                    if (history.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(onClick = { navigateHistory(up = true) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowUp, null, tint = TermComment, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { navigateHistory(up = false) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.KeyboardArrowDown, null, tint = TermComment, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Send button
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(AIRIShapes.xs)
                            .background(
                                if (input.isNotBlank() && !isRunning) TermPrompt.copy(0.25f)
                                else TermBorder
                            )
                            .clickable(enabled = input.isNotBlank() && !isRunning) { submitCommand() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Send,
                            null,
                            tint = if (input.isNotBlank() && !isRunning) CosmicAccent else TermComment,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

// ── Terminal line renderer with syntax colouring ──────────────────────────────
@Composable
private fun TerminalLineRow(line: TerminalRuntime.TerminalLine, searchQuery: String) {
    val text = line.text

    val annotated = remember(text, line.isInput) {
        buildAnnotatedString {
            if (line.isInput) {
                // Colour user input with syntax highlighting
                withStyle(SpanStyle(color = TermInput, fontWeight = FontWeight.Medium)) {
                    append(text)
                }
            } else {
                // Highlight search matches
                if (searchQuery.isNotBlank()) {
                    var start = 0
                    val lower = text.lowercase()
                    val query = searchQuery.lowercase()
                    while (kotlinx.coroutines.isActive) {
                        val idx = lower.indexOf(query, start)
                        if (idx < 0) { append(text.substring(start)); break }
                        append(text.substring(start, idx))
                        withStyle(SpanStyle(background = CosmicAccent.copy(0.35f), color = TermOutput)) {
                            append(text.substring(idx, idx + query.length))
                        }
                        start = idx + query.length
                    }
                } else {
                    append(text)
                }
            }
        }
    }

    val baseColor = when {
        line.isInput -> TermInput
        line.isError -> TermError
        text.startsWith("warn", ignoreCase = true) || text.startsWith("warning", ignoreCase = true) -> TermWarn
        text.startsWith("ok", ignoreCase = true) || text.startsWith("success", ignoreCase = true) -> TermSuccess
        text.startsWith("#") -> TermComment
        else -> TermOutput.copy(0.78f)
    }

    Text(
        text = annotated,
        color = if (searchQuery.isBlank()) baseColor else TermOutput.copy(0.78f),
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        lineHeight = 17.sp,
        modifier = Modifier.fillMaxWidth()
    )
}

// Compatibility shim for BasicTextField2 (doesn't exist in older Compose)
@Composable
private fun BasicTextField2Compat(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        textStyle = TextStyle(color = TermOutput, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(CosmicAccent),
        singleLine = true,
        decorationBox = { inner ->
            if (value.isEmpty()) Text(placeholder, color = TermComment, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            inner()
        }
    )
}
