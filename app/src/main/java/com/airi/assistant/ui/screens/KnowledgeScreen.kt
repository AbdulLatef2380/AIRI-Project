package com.airi.assistant.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airi.assistant.ui.components.NeuralBadge
import com.airi.assistant.ui.components.NeuralBottomSheet
import com.airi.assistant.ui.components.NeuralSearchBar
import com.airi.assistant.ui.components.NeuralScreenHeader
import com.airi.assistant.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject

// ─────────────────────────────────────────────────────────────────────────────
// Local data model — no external dependencies, no Room, no network.
// Backed by SharedPreferences (JSON array) for persistence across process death.
// ─────────────────────────────────────────────────────────────────────────────

private enum class KnowledgeType { TEXT, URL, DOCUMENT }

private data class KnowledgeEntry(
    val id:        String,
    val title:     String,
    val content:   String,
    val type:      KnowledgeType,
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
            put("id", e.id)
            put("title", e.title)
            put("content", e.content)
            put("type", e.type.name)
            put("createdAt", e.createdAt)
        })
    }
    context.getSharedPreferences(PREFS_KEY, android.content.Context.MODE_PRIVATE)
        .edit()
        .putString(DATA_KEY, arr.toString())
        .apply()
}

// ─────────────────────────────────────────────────────────────────────────────
// Screen
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun KnowledgeScreen(onBack: () -> Unit) {
    val context  = LocalContext.current
    var entries  by remember { mutableStateOf(loadEntries(context)) }
    var query    by remember { mutableStateOf("") }
    var showAdd  by remember { mutableStateOf(false) }

    var typeFilter by remember { mutableStateOf<KnowledgeType?>(null) }

    val filtered = remember(entries, query, typeFilter) {
        entries
            .let { list ->
                if (typeFilter != null) list.filter { it.type == typeFilter } else list
            }
            .let { list ->
                if (query.isBlank()) list
                else list.filter {
                    it.title.contains(query, ignoreCase = true) ||
                    it.content.contains(query, ignoreCase = true)
                }
            }
    }

    fun save(list: List<KnowledgeEntry>) {
        entries = list
        saveEntries(context, list)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface0)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            NeuralScreenHeader(title = "قاعدة المعرفة", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                NeuralSearchBar(
                    query         = query,
                    onQueryChange = { query = it },
                    placeholder   = "البحث في المعرفة..."
                )

                Spacer(Modifier.height(8.dp))

                // Filter chips
                val chipDefs = listOf<Pair<String, KnowledgeType?>>(
                    "الكل" to null,
                    "نص"  to KnowledgeType.TEXT,
                    "URL" to KnowledgeType.URL,
                    "مستند" to KnowledgeType.DOCUMENT
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    chipDefs.forEach { (label, type) ->
                        FilterChipItem(
                            label    = label,
                            selected = typeFilter == type,
                            onClick  = { typeFilter = type }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty() && entries.isEmpty()) {
                    EmptyKnowledge(onAdd = { showAdd = true })
                } else if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد نتائج لـ \"$query\"", color = TextTertiary, fontSize = 14.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 100.dp)
                    ) {
                        items(filtered, key = { it.id }) { entry ->
                            KnowledgeCard(
                                entry    = entry,
                                onDelete = {
                                    save(entries.filter { it.id != entry.id })
                                }
                            )
                        }
                    }
                }
            }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(PrimaryAccent)
                .clickable { showAdd = true },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Add, contentDescription = "إضافة", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }

    // Add entry bottom sheet
    if (showAdd) {
        AddKnowledgeSheet(
            onDismiss = { showAdd = false },
            onAdd     = { newEntry ->
                save(entries + newEntry)
                showAdd = false
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun EmptyKnowledge(onAdd: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(PrimaryAccent.copy(alpha = 0.12f))
                    .border(1.dp, PrimaryAccent.copy(alpha = 0.25f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.MenuBook, contentDescription = null, tint = PrimaryAccent, modifier = Modifier.size(36.dp))
            }
            Text("قاعدة المعرفة فارغة", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("أضف نصوصاً أو روابط أو مستندات يمكن لـ AIRI الاستناد إليها.", color = TextSecondary, fontSize = 13.sp)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(PrimaryAccent)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 22.dp, vertical = 11.dp)
            ) {
                Text("إضافة معرفة", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun KnowledgeCard(entry: KnowledgeEntry, onDelete: () -> Unit) {
    val (icon, color) = when (entry.type) {
        KnowledgeType.TEXT     -> Icons.Default.Article to PrimaryAccent
        KnowledgeType.URL      -> Icons.Default.Link    to SemanticSuccess
        KnowledgeType.DOCUMENT -> Icons.Default.Article to SecondaryAccent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface2)
            .border(1.dp, BorderLight, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    entry.title,
                    color      = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize   = 14.sp,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.weight(1f, fill = false)
                )
                NeuralBadge(
                    text  = entry.type.name,
                    color = color
                )
            }
            Text(
                entry.content,
                color    = TextSecondary,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "حذف", tint = SemanticError.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun FilterChipItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) PrimaryAccent.copy(alpha = 0.15f) else Surface2)
            .border(
                1.dp,
                if (selected) PrimaryAccent.copy(alpha = 0.40f) else BorderLight,
                RoundedCornerShape(999.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color      = if (selected) PrimaryAccent else TextSecondary,
            fontSize   = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun AddKnowledgeSheet(
    onDismiss: () -> Unit,
    onAdd:     (KnowledgeEntry) -> Unit
) {
    var title    by remember { mutableStateOf("") }
    var content  by remember { mutableStateOf("") }
    var type     by remember { mutableStateOf(KnowledgeType.TEXT) }

    NeuralBottomSheet(
        onDismiss = onDismiss,
        title     = "إضافة معرفة جديدة"
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Type selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnowledgeType.values().forEach { t ->
                    val sel = t == type
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) PrimaryAccent.copy(alpha = 0.15f) else Surface3)
                            .border(1.dp, if (sel) PrimaryAccent.copy(alpha = 0.40f) else BorderLight, RoundedCornerShape(8.dp))
                            .clickable { type = t }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = when (t) {
                                KnowledgeType.TEXT     -> "نص"
                                KnowledgeType.URL      -> "رابط"
                                KnowledgeType.DOCUMENT -> "مستند"
                            },
                            color      = if (sel) PrimaryAccent else TextSecondary,
                            fontSize   = 13.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }

            // Title field
            OutlinedTextField(
                value         = title,
                onValueChange = { title = it },
                label         = { Text("العنوان", color = TextSecondary) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = PrimaryAccent,
                    unfocusedBorderColor = BorderLight,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = PrimaryAccent,
                    focusedContainerColor   = Surface2,
                    unfocusedContainerColor = Surface2,
                )
            )

            // Content field
            OutlinedTextField(
                value         = content,
                onValueChange = { content = it },
                label         = {
                    Text(
                        if (type == KnowledgeType.URL) "الرابط (URL)" else "المحتوى",
                        color = TextSecondary
                    )
                },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                colors   = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = PrimaryAccent,
                    unfocusedBorderColor = BorderLight,
                    focusedTextColor     = TextPrimary,
                    unfocusedTextColor   = TextPrimary,
                    cursorColor          = PrimaryAccent,
                    focusedContainerColor   = Surface2,
                    unfocusedContainerColor = Surface2,
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Surface3)
                        .border(1.dp, BorderLight, RoundedCornerShape(10.dp))
                        .clickable(onClick = onDismiss)
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("إلغاء", color = TextSecondary, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (title.isBlank() || content.isBlank()) PrimaryAccent.copy(alpha = 0.4f) else PrimaryAccent)
                        .clickable(enabled = title.isNotBlank() && content.isNotBlank()) {
                            onAdd(KnowledgeEntry(
                                id      = System.currentTimeMillis().toString(),
                                title   = title.trim(),
                                content = content.trim(),
                                type    = type
                            ))
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("حفظ", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}
