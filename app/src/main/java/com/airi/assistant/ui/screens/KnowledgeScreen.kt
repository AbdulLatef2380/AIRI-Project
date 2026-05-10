package com.airi.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.*
import com.airi.assistant.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

private enum class KnowledgeType { TEXT, URL, DOCUMENT }

private data class KnowledgeEntry(
    val id: String,
    val title: String,
    val content: String,
    val type: KnowledgeType,
    val createdAt: Long = System.currentTimeMillis()
)

private const val PREFS_KEY = "airi_knowledge"
private const val DATA_KEY  = "entries"

private fun loadEntries(context: android.content.Context): List<KnowledgeEntry> {
    val prefs = context.getSharedPreferences(PREFS_KEY, android.content.Context.MODE_PRIVATE)
    val json  = prefs.getString(DATA_KEY, "[]") ?: "[]"
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            KnowledgeEntry(
                id        = obj.getString("id"),
                title     = obj.getString("title"),
                content   = obj.getString("content"),
                type      = KnowledgeType.valueOf(obj.optString("type", KnowledgeType.TEXT.name)),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis())
            )
        }
    } catch (_: Throwable) { emptyList() }
}

private fun saveEntries(context: android.content.Context, entries: List<KnowledgeEntry>) {
    val arr = JSONArray()
    entries.forEach { e ->
        arr.put(JSONObject().apply {
            put("id", e.id); put("title", e.title); put("content", e.content)
            put("type", e.type.name); put("createdAt", e.createdAt)
        })
    }
    context.getSharedPreferences(PREFS_KEY, android.content.Context.MODE_PRIVATE)
        .edit().putString(DATA_KEY, arr.toString()).apply()
}

@Composable
fun KnowledgeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(loadEntries(context)) }
    var query   by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf<KnowledgeType?>(null) }

    val filtered = remember(entries, query, typeFilter) {
        entries.filter { e ->
            (typeFilter == null || e.type == typeFilter) &&
            (query.isBlank() || e.title.contains(query, ignoreCase = true) || e.content.contains(query, ignoreCase = true))
        }
    }

    Scaffold(
        containerColor = Surface0,
        topBar = {
            AiriScreenHeader(title = "المعرفة", onBack = onBack) {
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Add", tint = PrimaryAccent)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search + filter row
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeuralSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "بحث في المعرفة...",
                    modifier = Modifier.weight(1f)
                )
            }

            // Type filter chips
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                KnowledgeTypeChip("الكل", typeFilter == null) { typeFilter = null }
                KnowledgeTypeChip("نص", typeFilter == KnowledgeType.TEXT) { typeFilter = KnowledgeType.TEXT }
                KnowledgeTypeChip("رابط", typeFilter == KnowledgeType.URL) { typeFilter = KnowledgeType.URL }
                KnowledgeTypeChip("مستند", typeFilter == KnowledgeType.DOCUMENT) { typeFilter = KnowledgeType.DOCUMENT }
            }

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                        Text("لا توجد معرفة مضافة بعد", color = TextTertiary, fontSize = 14.sp)
                        NeuralAccentButton("إضافة معرفة", onClick = { showAdd = true }, modifier = Modifier.width(200.dp))
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        KnowledgeCard(
                            entry = entry,
                            onDelete = {
                                val updated = entries.filterNot { it.id == entry.id }
                                entries = updated
                                saveEntries(context, updated)
                            }
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        AddKnowledgeSheet(
            onDismiss = { showAdd = false },
            onSave = { title, content, type ->
                val entry = KnowledgeEntry(
                    id = UUID.randomUUID().toString(),
                    title = title, content = content, type = type
                )
                val updated = listOf(entry) + entries
                entries = updated
                saveEntries(context, updated)
                showAdd = false
            }
        )
    }
}

@Composable
private fun KnowledgeCard(entry: KnowledgeEntry, onDelete: () -> Unit) {
    var showDelete by remember { mutableStateOf(false) }
    val typeIcon: ImageVector = when (entry.type) {
        KnowledgeType.URL      -> Icons.Default.Link
        KnowledgeType.DOCUMENT -> Icons.Default.Article
        else                   -> Icons.Outlined.MenuBook
    }
    val typeColor = when (entry.type) {
        KnowledgeType.URL      -> AccentCloud
        KnowledgeType.DOCUMENT -> SemanticWarning
        else                   -> PrimaryAccent
    }
    val fmt = remember { SimpleDateFormat("d MMM", Locale("ar")) }
    val dateStr = remember(entry.createdAt) { fmt.format(Date(entry.createdAt)) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface1)
            .border(1.dp, BorderLight, RoundedCornerShape(16.dp))
            .clickable { showDelete = !showDelete }
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(typeColor.copy(alpha = 0.14f))
                        .border(0.5.dp, typeColor.copy(alpha = 0.3f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(typeIcon, contentDescription = null, tint = typeColor, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entry.content, color = TextSecondary, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
                }
            }
            AnimatedVisibility(visible = showDelete) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.textButtonColors(contentColor = SemanticError)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("حذف", fontWeight = FontWeight.Medium)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeuralBadge(
                    text = when (entry.type) {
                        KnowledgeType.URL -> "رابط"
                        KnowledgeType.DOCUMENT -> "مستند"
                        else -> "نص"
                    },
                    color = typeColor
                )
                Text(dateStr, color = TextTertiary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun KnowledgeTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) PrimaryAccent else Surface2)
            .border(1.dp, if (selected) PrimaryAccent else BorderLight, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(label, color = if (selected) Color.White else TextSecondary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun AddKnowledgeSheet(
    onDismiss: () -> Unit,
    onSave: (title: String, content: String, type: KnowledgeType) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(KnowledgeType.TEXT) }

    NeuralBottomSheet(onDismiss = onDismiss, title = "إضافة معرفة") {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            NeuralSearchBar(value = title, onValueChange = { title = it }, placeholder = "العنوان...")
            
            // Type selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnowledgeTypeChip("نص", type == KnowledgeType.TEXT) { type = KnowledgeType.TEXT }
                KnowledgeTypeChip("رابط", type == KnowledgeType.URL) { type = KnowledgeType.URL }
                KnowledgeTypeChip("مستند", type == KnowledgeType.DOCUMENT) { type = KnowledgeType.DOCUMENT }
            }

            BasicTextField(
                value = content,
                onValueChange = { content = it },
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                cursorBrush = SolidColor(AccentBlue),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface3)
                    .border(1.dp, BorderLow, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                decorationBox = { innerTextField ->
                    if (content.isEmpty()) {
                        Text("أدخل المحتوى هنا...", color = TextTertiary, fontSize = 15.sp)
                    }
                    innerTextField()
                }
            )

            NeuralAccentButton(
                text = "حفظ",
                onClick = { if (title.isNotBlank() && content.isNotBlank()) onSave(title, content, type) },
                enabled = title.isNotBlank() && content.isNotBlank()
            )
        }
    }
}
