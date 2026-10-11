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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.airi.assistant.R
import com.airi.assistant.memory.entity.ChatMessage  // Long id, non-nullable
import com.airi.assistant.ui.theme.*
import com.airi.assistant.ui.viewmodel.ChatViewModel
import com.airi.assistant.core.ServiceLocator
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
    var deleteCandidate by remember { mutableStateOf<ChatMessage?>(null) }
    var editCandidate  by remember { mutableStateOf<ChatMessage?>(null) }
    var editDraft      by remember { mutableStateOf("") }
    var searchQuery    by remember { mutableStateOf("") }
    var showSearch     by remember { mutableStateOf(false) }
    var showKnowledgeDialog by remember { mutableStateOf(false) }
    var selectedKnowledge by remember { mutableStateOf<com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile?>(null) }
    var editingKnowledge by remember { mutableStateOf<com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile?>(null) }
    var deletingKnowledge by remember { mutableStateOf<com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile?>(null) }
    val knowledgeManager = remember { ServiceLocator.projectKnowledgeManager }
    val knowledgeProfiles by knowledgeManager.profiles.collectAsState()
    val context        = LocalContext.current
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
            containerColor   = AiriTheme.surface,
            shape            = AIRIShapes.xl,
            icon             = { Icon(Icons.Outlined.DeleteSweep, null, tint = SemanticError, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.memory_clear_confirm_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) },
            text  = { Text(stringResource(R.string.memory_clear_dialog_body), color = AiriTheme.onSurfaceVariant, lineHeight = 20.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirm = false
                        scope.launch {
                            viewModel.clearMemory()
                                .onSuccess {
                                    snackbarHost.showSnackbar(context.getString(R.string.memory_cleared_snack))
                                }
                                .onFailure {
                                    snackbarHost.showSnackbar(context.getString(R.string.memory_clear_failed_snack))
                                }
                        }
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

    deleteCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = AiriTheme.surface,
            shape = AIRIShapes.xl,
            icon = { Icon(Icons.Outlined.Delete, null, tint = SemanticError, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.memory_delete_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.memory_delete_body), color = AiriTheme.onSurfaceVariant, lineHeight = 20.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val removed = runCatching { viewModel.deleteMemoryEntry(candidate.id) }.getOrDefault(false)
                            deleteCandidate = null
                            snackbarHost.showSnackbar(
                                context.getString(
                                    if (removed) R.string.memory_deleted_snack else R.string.memory_delete_failed
                                )
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError),
                    shape = AIRIShapes.md
                ) { Text(stringResource(R.string.memory_delete_button), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    editCandidate?.let { candidate ->
        AlertDialog(
            onDismissRequest = { editCandidate = null },
            containerColor = AiriTheme.surface,
            shape = AIRIShapes.xl,
            icon = { Icon(Icons.Outlined.Edit, null, tint = CosmicAccent, modifier = Modifier.size(28.dp)) },
            title = { Text(stringResource(R.string.memory_edit_title), color = AiriTheme.onBackground, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    label = { Text(stringResource(R.string.memory_edit_content_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CosmicAccent,
                        unfocusedBorderColor = AiriTheme.outline,
                        focusedTextColor = AiriTheme.onSurface,
                        unfocusedTextColor = AiriTheme.onSurface
                    )
                )
            },
            confirmButton = {
                Button(
                    enabled = editDraft.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val updated = runCatching {
                                viewModel.editMemoryEntry(candidate.id, editDraft)
                            }.getOrDefault(false)
                            if (updated) editCandidate = null
                            snackbarHost.showSnackbar(
                                context.getString(
                                    if (updated) R.string.memory_edited_snack else R.string.memory_edit_failed
                                )
                            )
                        }
                    },
                    shape = AIRIShapes.md
                ) { Text(stringResource(R.string.memory_edit_save), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { editCandidate = null }) {
                    Text(stringResource(R.string.cancel), color = AiriTheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showKnowledgeDialog) {
        KnowledgeProfilesDialog(knowledgeProfiles, { showKnowledgeDialog = false },
            { profile -> showKnowledgeDialog = false; selectedKnowledge = profile },
            { name, usage, content -> knowledgeManager.createKnowledge(name, usage, content) },
            { id, active -> knowledgeManager.setKnowledgeActive(id, active) })
    }
    selectedKnowledge?.let { profile ->
        KnowledgeProfileDetailDialog(
            profile = knowledgeProfiles.firstOrNull { it.id == profile.id } ?: profile,
            onDismiss = { selectedKnowledge = null },
            onToggle = { id, active -> knowledgeManager.setKnowledgeActive(id, active) },
            onEdit = { current -> selectedKnowledge = null; editingKnowledge = current },
            onDelete = { current -> selectedKnowledge = null; deletingKnowledge = current }
        )
    }
    editingKnowledge?.let { profile ->
        KnowledgeProfileEditorDialog(
            profile = profile,
            onDismiss = { editingKnowledge = null },
            onSave = { id, name, usage, content ->
                if (knowledgeManager.updateKnowledge(id, name, usage, content) != null) editingKnowledge = null
            }
        )
    }
    deletingKnowledge?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingKnowledge = null },
            containerColor = AiriTheme.surface,
            title = { Text("حذف المعرفة؟", color = AiriTheme.onSurface, fontWeight = FontWeight.Bold) },
            text = { Text("سيتم حذف المعرفة «${profile.name}» نهائياً من قائمة المعرفة.", color = AiriTheme.onSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = { knowledgeManager.deleteKnowledge(profile.id); deletingKnowledge = null },
                    colors = ButtonDefaults.buttonColors(containerColor = SemanticError)
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { deletingKnowledge = null }) { Text("إلغاء") } }
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
                        IconButton(onClick = { showKnowledgeDialog = true }) {
                            Icon(Icons.Outlined.MenuBook, "Knowledge", tint = CosmicAccent, modifier = Modifier.size(20.dp))
                        }
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
                Divider(color = AiriTheme.outline)
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
                    Text(stringResource(R.string.memory_search_no_results, searchQuery), fontSize = 13.sp, color = AiriTheme.onSurfaceVariant)
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
                                stringResource(R.string.memory_admission_summary),
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
                    MemoryEntryCard(
                        msg = msg,
                        searchQuery = searchQuery,
                        onEdit = if (msg.isMemory) ({
                            editCandidate = msg
                            editDraft = msg.content
                        }) else null,
                        onDelete = if (msg.isMemory) ({ deleteCandidate = msg }) else null
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryEntryCard(
    msg: ChatMessage,
    searchQuery: String,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    val isUser  = msg.role == "user"
    val timeStr = remember(msg.timestamp) {
        SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
    }

    Surface(
        shape  = AIRIShapes.md,
        color  = AiriTheme.surfaceVariant,
        border = BorderStroke(0.5.dp, AiriTheme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier.size(22.dp).clip(CircleShape)
                        .background(if (isUser) CosmicAccent.copy(0.20f) else AiriTheme.surfaceVariant),
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
                        if (isUser) stringResource(R.string.memory_role_user) else stringResource(R.string.memory_role_assistant),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) CosmicAccent else AiriTheme.onBackground
                )
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    color = if (msg.isMemory) CosmicAccent.copy(alpha = 0.14f) else AiriTheme.surfaceVariant
                ) {
                    Text(
                        if (msg.isMemory) stringResource(R.string.memory_badge_long_term) else stringResource(R.string.memory_badge_context),
                        fontSize = 9.sp,
                        color = if (msg.isMemory) CosmicAccent else AiriTheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(timeStr, fontSize = 10.sp, color = AiriTheme.onSurfaceVariant.copy(0.55f))
                if (onEdit != null) {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.memory_edit_title),
                            tint = CosmicAccent.copy(alpha = 0.82f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                if (onDelete != null) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.memory_delete_button),
                            tint = SemanticError.copy(alpha = 0.78f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Text(
                msg.content,
                fontSize  = 13.sp,
                color     = AiriTheme.onBackground.copy(0.82f),
                maxLines  = 4,
                overflow  = TextOverflow.Ellipsis,
                lineHeight = 18.sp
            )
            if (msg.isMemory) {
                val provenance = msg.provenance.ifBlank { msg.memorySource.replace('_', ' ').lowercase() }
                val confidencePercent = (msg.confidence.coerceIn(0f, 1f) * 100).toInt()
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.memory_why_saved),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = CosmicAccent
                    )
                    Text(
                        provenance,
                        fontSize = 11.sp,
                        color = AiriTheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        stringResource(
                            R.string.memory_scope_confidence,
                            msg.memoryScope.lowercase(),
                            confidencePercent
                        ),
                        fontSize = 10.sp,
                        color = AiriTheme.onSurfaceVariant.copy(alpha = 0.72f)
                    )
                }
            }
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
        color  = AiriTheme.surfaceVariant,
        border = BorderStroke(0.5.dp, if (query.isNotBlank()) CosmicAccent.copy(0.35f) else AiriTheme.outline),
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


@Composable
private fun KnowledgeProfilesDialog(
    profiles: List<com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile>,
    onDismiss: () -> Unit,
    onOpen: (com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile) -> Unit,
    onCreate: (String, String, String) -> Unit,
    onToggle: (String, Boolean) -> Unit
) {
    var creating by remember { mutableStateOf(profiles.isEmpty()) }
    var name by remember { mutableStateOf("") }
    var usage by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AiriTheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, "رجوع", tint = AiriTheme.onBackground) }
                    Text("المعرفة", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = AiriTheme.onBackground, modifier = Modifier.weight(1f))
                    IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "إضافة معرفة", tint = CosmicAccent) }
                }
                Divider(color = AiriTheme.outline)
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profiles, key = { it.id }) { profile ->
                        Surface(onClick = { onOpen(profile) }, shape = AIRIShapes.xl, color = AiriTheme.surface, border = BorderStroke(.5.dp, if (profile.active) CosmicAccent.copy(.28f) else AiriTheme.outline), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (profile.active) CosmicAccent else AiriTheme.outline))
                                    Spacer(Modifier.width(8.dp)); Text(profile.name, color = AiriTheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                    Text(if (profile.active) "مفعل" else "معطل", color = if (profile.active) CosmicAccent else AiriTheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Text(profile.usage, color = AiriTheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Divider(color = AiriTheme.outline.copy(.6f))
                                Text(profile.content, color = AiriTheme.onBackground.copy(.78f), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                    Text("استخدام المعرفة", color = AiriTheme.onSurfaceVariant, fontSize = 11.sp); Spacer(Modifier.width(8.dp)); Switch(profile.active, { onToggle(profile.id, it) })
                                }
                            }
                        }
                    }
                    if (creating) item {
                        KnowledgeEditorFields("معرفة جديدة", name, usage, content, { name = it }, { usage = it }, { content = it }) {
                            if (name.isNotBlank() && usage.isNotBlank() && content.isNotBlank()) { onCreate(name, usage, content); name = ""; usage = ""; content = ""; creating = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeProfileDetailDialog(
    profile: com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile,
    onDismiss: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onEdit: (com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile) -> Unit,
    onDelete: (com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile) -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AiriTheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, "رجوع", tint = AiriTheme.onBackground) }
                    Text(profile.name, color = AiriTheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "المزيد", tint = AiriTheme.onBackground) }
                        DropdownMenu(menuOpen, { menuOpen = false }, modifier = Modifier.background(AiriTheme.surface)) {
                            DropdownMenuItem(
                                text = { Text("تعديل") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                                onClick = { menuOpen = false; onEdit(profile) }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف", color = SemanticError) },
                                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = SemanticError) },
                                onClick = { menuOpen = false; onDelete(profile) }
                            )
                        }
                    }
                }
                Divider(color = AiriTheme.outline)
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    KnowledgeDetailBlock("تستخدم عندما...", profile.usage)
                    KnowledgeDetailBlock("المحتوى", profile.content)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { onToggle(profile.id, !profile.active) }, modifier = Modifier.fillMaxWidth().padding(20.dp), colors = ButtonDefaults.buttonColors(containerColor = if (profile.active) AiriTheme.surfaceVariant else CosmicAccent, contentColor = if (profile.active) SemanticError else Color.White), shape = AIRIShapes.lg) { Text(if (profile.active) "تعطيل المعرفة" else "تفعيل المعرفة", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun KnowledgeDetailBlock(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = AiriTheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Surface(shape = AIRIShapes.lg, color = AiriTheme.surface, border = BorderStroke(.5.dp, AiriTheme.outline), modifier = Modifier.fillMaxWidth()) { Text(value, color = AiriTheme.onSurface, fontSize = 15.sp, lineHeight = 23.sp, modifier = Modifier.padding(16.dp)) }
    }
}

@Composable
private fun KnowledgeEditorFields(title: String, name: String, usage: String, content: String, onName: (String) -> Unit, onUsage: (String) -> Unit, onContent: (String) -> Unit, onSave: () -> Unit) {
    Surface(shape = AIRIShapes.xl, color = AiriTheme.surfaceVariant, border = BorderStroke(1.dp, CosmicAccent.copy(.3f)), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = AiriTheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            KnowledgeField("اسم المعرفة", name, onName, "اسم واضح وقابل للبحث")
            KnowledgeField("تستخدم عندما...", usage, onUsage, "متى يحتاجها AIRI؟")
            KnowledgeField("المحتوى", content, onContent, "اكتب المعرفة هنا", 5)
            Button(onClick = onSave, enabled = name.isNotBlank() && usage.isNotBlank() && content.isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = CosmicAccent), shape = AIRIShapes.md) { Text("حفظ المعرفة", fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun KnowledgeProfileEditorDialog(profile: com.airi.assistant.knowledge.ProjectKnowledgeManager.KnowledgeProfile, onDismiss: () -> Unit, onSave: (String, String, String, String) -> Unit) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var usage by remember(profile.id) { mutableStateOf(profile.usage) }
    var content by remember(profile.id) { mutableStateOf(profile.content) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = AiriTheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "إلغاء", tint = AiriTheme.onBackground) }
                    Text("تعديل المعرفة", color = AiriTheme.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { if (name.isNotBlank() && usage.isNotBlank() && content.isNotBlank()) onSave(profile.id, name, usage, content) }) { Text("حفظ", color = CosmicAccent, fontWeight = FontWeight.Bold) }
                }
                Divider(color = AiriTheme.outline)
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KnowledgeField("اسم المعرفة", name, { name = it }, "اسم واضح")
                    KnowledgeField("تستخدم عندما...", usage, { usage = it }, "اشرح الحالة المناسبة")
                    KnowledgeField("المحتوى", content, { content = it }, "محتوى المعرفة", 10)
                }
            }
        }
    }
}

@Composable
private fun KnowledgeField(label: String, value: String, onChange: (String) -> Unit, hint: String, minLines: Int = 2) {
    OutlinedTextField(value, onChange, label = { Text(label) }, placeholder = { Text(hint, color = AiriTheme.outline) }, modifier = Modifier.fillMaxWidth(), minLines = minLines, maxLines = if (minLines > 4) 16 else 4, shape = AIRIShapes.md, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CosmicAccent, unfocusedBorderColor = AiriTheme.outline, focusedLabelColor = CosmicAccent, focusedTextColor = AiriTheme.onSurface, unfocusedTextColor = AiriTheme.onSurface, cursorColor = CosmicAccent))
}
